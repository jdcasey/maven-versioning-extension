package org.commonjava.maven.ext.versioning;

import static org.commonjava.maven.ext.versioning.IdUtils.gav;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.sonatype.aether.RepositorySystem;
import org.sonatype.aether.metadata.Metadata;
import org.sonatype.aether.metadata.Metadata.Nature;
import org.sonatype.aether.repository.RemoteRepository;
import org.sonatype.aether.resolution.MetadataRequest;
import org.sonatype.aether.resolution.MetadataResult;
import org.sonatype.aether.util.metadata.DefaultMetadata;

@Component( role = VersionCalculator.class )
public class VersionCalculator
{

    private static final String SERIAL_SUFFIX_PATTERN = "([^-.]+)(?:([-.])(\\d+))?$";

    public static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

    @Requirement
    private RepositorySystem repositorySystem;

    @Requirement
    private Logger logger;

    public VersionCalculator()
    {
    }

    public VersionCalculator( final RepositorySystem repositorySystem, final Logger logger )
    {
        this.repositorySystem = repositorySystem;
        this.logger = logger;
    }

    public Map<String, String> calculateVersioningChanges( final Collection<MavenProject> projects )
        throws VersionModifierException
    {
        final Map<String, VersionCalculation> calculationsByGA = new HashMap<String, VersionCalculation>();
        boolean incremental = false;
        for ( final MavenProject project : projects )
        {
            final String originalVersion = project.getVersion();

            final VersionCalculation modifiedVersion =
                calculate( project.getGroupId(), project.getArtifactId(), originalVersion );

            logger.info( "\n\nModifying version of: " + project.getGroupId() + ":" + project.getArtifactId()
                + "\n    from: " + project.getVersion() + "\n    to: " + modifiedVersion );

            if ( modifiedVersion.hasCalculation() )
            {
                incremental = incremental || modifiedVersion.isIncremental();
                calculationsByGA.put( gav( project ), modifiedVersion );
            }
        }

        if ( incremental )
        {
            int maxQualifier = 1;
            for ( final VersionCalculation calc : calculationsByGA.values() )
            {
                final int i = calc.getIncrementalQualifier();
                maxQualifier = maxQualifier > i ? maxQualifier : i;
            }

            for ( final VersionCalculation calc : calculationsByGA.values() )
            {
                calc.setIncrementalQualifier( maxQualifier );
            }
        }

        final Map<String, String> versionsByGA = new HashMap<String, String>();
        for ( final Map.Entry<String, VersionCalculation> entry : calculationsByGA.entrySet() )
        {
            versionsByGA.put( entry.getKey(), entry.getValue()
                                                   .renderVersion() );
        }

        return versionsByGA;
    }

    VersionCalculation calculate( final String groupId, final String artifactId, final String originalVersion )
        throws VersionModifierException
    {
        String baseVersion = originalVersion;

        boolean snapshot = false;
        // If we're building a snapshot, make sure the resulting version ends
        // in "-SNAPSHOT"
        if ( baseVersion.endsWith( SNAPSHOT_SUFFIX ) )
        {
            snapshot = true;
            baseVersion = baseVersion.substring( 0, baseVersion.length() - SNAPSHOT_SUFFIX.length() );
        }

        final VersioningSession session = VersioningSession.getInstance();
        final String incrementalSerialSuffix = session.getIncrementalSerialSuffix();
        final String suffix = session.getSuffix();

        logger.debug( "Got the following version suffixes:\n  Static: " + suffix + "\nIncremental: " + incrementalSerialSuffix );

        final String suff = suffix != null ? suffix : incrementalSerialSuffix;

        logger.debug( "Using suffix: " + suff );
        final Pattern serialSuffixPattern = Pattern.compile( SERIAL_SUFFIX_PATTERN );
        final Matcher suffixMatcher = serialSuffixPattern.matcher( suff );

        final VersionCalculation vc = new VersionCalculation( originalVersion, baseVersion );
        if ( suffixMatcher.matches() )
        {
            // the "redhat" in "redhat-1"
            final String suffixBase = suffixMatcher.group( 1 );
            String sep = suffixMatcher.group( 2 );
            if ( sep == null )
            {
                sep = "-";
            }

            final int idx = baseVersion.indexOf( suffixBase );

            if ( idx > 1 )
            {
                // trim the old suffix off.
                baseVersion = baseVersion.substring( 0, idx - 1 );
                vc.setBaseVersion( baseVersion );
                logger.debug( "Trimmed version (without pre-existing suffix): " + baseVersion );
            }

            // If we're using serial suffixes (-redhat-N) and the flag is set
            // to increment the existing suffix, read available versions from the
            // existing POM, plus the repository metadata, and find the highest
            // serial number to increment...then increment it.
            if ( suff.equals( incrementalSerialSuffix ) )
            {
                logger.debug( "Resolving suffixes already found in metadata to determine increment base." );

                vc.setVersionSuffix( suffixBase );

                final List<String> versionCandidates = new ArrayList<String>();
                versionCandidates.add( originalVersion );
                versionCandidates.addAll( getMetadataVersions( groupId, artifactId, session ) );

                int maxSerial = 0;

                for ( final String version : versionCandidates )
                {
                    final Matcher candidateSuffixMatcher = serialSuffixPattern.matcher( version );

                    if ( candidateSuffixMatcher.find() )
                    {
                        final String wholeSuffix = candidateSuffixMatcher.group();
                        logger.debug( "Group 0 of serial-suffix matcher is: '" + wholeSuffix + "'" );
                        final int baseIdx = version.indexOf( wholeSuffix );

                        // Need room for at least a character in the base-version, plus a separator like '-'
                        if ( baseIdx < 2 )
                        {
                            logger.debug( "Ignoring invalid version: '" + version + "' (seems to be naked version suffix with no base)." );
                            continue;
                        }

                        final String base = version.substring( 0, baseIdx - 1 );
                        if ( !baseVersion.equals( base ) )
                        {
                            logger.debug( "Ignoring irrelevant version: '" + version + "' ('" + base
                                + "' doesn't match on base-version: '" + baseVersion
                                + "')." );
                            continue;
                        }

                        // grab the old serial number.
                        final String serialStr = candidateSuffixMatcher.group( 3 );
                        logger.debug( "Group 3 of serial-suffix matcher is: '" + serialStr + "'" );
                        final int serial = serialStr == null ? 0 : Integer.parseInt( serialStr );
                        if ( serial > maxSerial )
                        {
                            logger.debug( "new max serial number: " + serial + " (previous was: " + maxSerial + ")" );
                            maxSerial = serial;

                            // don't assume we're using '-' as suffix-base-to-serial-number separator...
                            sep = candidateSuffixMatcher.group( 2 );
                        }
                    }
                }

                vc.setSuffixSeparator( sep );
                vc.setIncrementalQualifier( maxSerial + 1 );
            }
            else
            {
                vc.setVersionSuffix( suff );
            }
        }
        // If we're not using a serial suffix, and the version already ends
        // with the chosen suffix, there's nothing to do!
        else if ( originalVersion.endsWith( suffix ) )
        {
            vc.clear();
            return vc;
        }

        // assume the version is of the form 1.2.3.GA, where appending the
        // suffix requires a '-' to concatenate the string of the final version
        // part in OSGi.
        String sep = "-";

        // now, check the above assumption...
        // if the version is of the form: 1.2.3, then we need to append the
        // suffix as a final version part using '.'
        logger.info( "Partial result: " + baseVersion );
        if ( baseVersion.matches( ".+[-.]\\d+" ) )
        {
            sep = ".";
        }

        vc.setBaseVersionSeparator( sep );

        // TODO OSGi fixup for versions like 1.2.GA or 1.2 (too few parts)

        // tack -SNAPSHOT back on if necessary...
        vc.setSnapshot( session.preserveSnapshot() && snapshot );
        //        {
        //            result += SNAPSHOT_SUFFIX;
        //        }

        return vc;
    }

    private Set<String> getMetadataVersions( final String groupId, final String artifactId, final VersioningSession session )
        throws VersionModifierException
    {
        logger.debug( "Reading available versions from repository metadata for: " + groupId + ":" + artifactId );

        final Set<String> versions = new HashSet<String>();
        final List<ArtifactRepository> remoteRepositories = session.getRequest()
                                                                   .getRemoteRepositories();
        for ( final ArtifactRepository repo : remoteRepositories )
        {
            final RemoteRepository remote = RepositoryUtils.toRepo( repo );

            logger.debug( "Checking: " + remote.getUrl() );
            resolveMetadata( groupId, artifactId, remote, versions, session );
        }

        return versions;
    }

    private void resolveMetadata( final String groupId, final String artifactId, final RemoteRepository remote, final Set<String> versions,
                                  final VersioningSession session )
        throws VersionModifierException
    {
        final MetadataRequest req =
            new MetadataRequest( new DefaultMetadata( groupId, artifactId, "maven-metadata.xml", Nature.RELEASE_OR_SNAPSHOT ), remote,
                                 "version-calculator" );

        req.setDeleteLocalCopyIfMissing( true );

        final List<MetadataRequest> reqs = new ArrayList<MetadataRequest>();
        reqs.add( req );

        final List<MetadataResult> mdResults = repositorySystem.resolveMetadata( session.getRepositorySystemSession(), reqs );

        if ( mdResults != null )
        {
            File mdFile = null;
            final MetadataXpp3Reader mdReader = new MetadataXpp3Reader();
            for ( final MetadataResult mdResult : mdResults )
            {
                Metadata metadata = mdResult.getMetadata();
                if ( metadata == null )
                {
                    metadata = mdResult.getRequest()
                                       .getMetadata();
                }

                if ( metadata == null )
                {
                    logger.error( "Cannot find metadata instance associated with MetadataResult: " + mdResult + ". Skipping..." );
                    continue;
                }

                mdFile = metadata.getFile();
                if ( mdFile == null )
                {
                    final Exception exception = mdResult.getException();
                    if ( exception != null )
                    {
                        if ( logger.isDebugEnabled() )
                        {
                            logger.error( "Failed to resolve metadata: " + metadata + ". Error: " + exception.getMessage(), exception );
                        }
                        else
                        {
                            logger.error( "Failed to resolve metadata: " + metadata + ". Error: " + exception.getMessage() );
                        }
                    }

                    continue;
                }

                logger.debug( "Reading: " + mdFile );
                FileInputStream stream = null;
                try
                {
                    stream = new FileInputStream( mdFile );
                    final org.apache.maven.artifact.repository.metadata.Metadata md = mdReader.read( stream );
                    final Versioning versioning = md.getVersioning();

                    if ( versioning != null )
                    {
                        logger.debug( "Got versions: " + versioning.getVersions() );
                        versions.addAll( versioning.getVersions() );
                    }
                }
                catch ( final IOException e )
                {
                    throw new VersionModifierException( "Cannot read metadata from: %s to determine last version-suffix serial number. Error: %s", e,
                                                        mdFile, e.getMessage() );
                }
                catch ( final XmlPullParserException e )
                {
                    throw new VersionModifierException( "Cannot parse metadata from: %s to determine last version-suffix serial number. Error: %s",
                                                        e, mdFile, e.getMessage() );
                }
                finally
                {
                    IOUtil.close( stream );
                }
            }
        }
    }

}
