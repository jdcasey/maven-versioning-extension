package org.commonjava.maven.ext.versioning;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.execution.MavenExecutionRequest;
import org.sonatype.aether.RepositorySystemSession;

public class VersioningSession
{

    public static final String VERSION_SUFFIX_SYSPROP = "version.suffix";

    public static final String INCREMENT_SERIAL_SUFFIX_SYSPROP = "version.incremental.suffix";

    public static final String VERSION_SUFFIX_SNAPSHOT_SYSPROP = "version.suffix.snapshot";

    private static VersioningSession INSTANCE = new VersioningSession();

    private MavenExecutionRequest request;

    private Map<String, String> versioningChanges;

    private final Set<String> changedGAVs = new HashSet<String>();

    // initially enabled, unless userProperties from the session turn it off...
    private boolean enabled;

    private String suffix;

    private String incrementSerialSuffix;

    private boolean preserveSnapshot;

    private RepositorySystemSession repositorySession;

    private File markerFile;

    // FIXME: Find SOME better way than a classical singleton to house this state!!!
    public static VersioningSession getInstance()
    {
        return INSTANCE;
    }

    public void setRequest( final MavenExecutionRequest request )
    {
        this.request = request;
        final File pom = request.getPom();

        if ( pom != null )
        {
            File dir = pom.getParentFile();
            if ( dir == null )
            {
                dir = pom.getAbsoluteFile()
                         .getParentFile();
            }

            markerFile = new File( dir, "target/versioning.log" );
        }

        final Properties userProps = request.getUserProperties();

        suffix = userProps.getProperty( VERSION_SUFFIX_SYSPROP );
        incrementSerialSuffix = userProps.getProperty( INCREMENT_SERIAL_SUFFIX_SYSPROP );
        preserveSnapshot = Boolean.parseBoolean( userProps.getProperty( VERSION_SUFFIX_SNAPSHOT_SYSPROP ) );

        this.enabled = pom != null && !markerFile.exists() && incrementSerialSuffix != null || suffix != null;
    }

    public File getMarkerFile()
    {
        return markerFile;
    }

    public String getIncrementalSerialSuffix()
    {
        return incrementSerialSuffix;
    }

    public String getSuffix()
    {
        return suffix;
    }

    public boolean preserveSnapshot()
    {
        return preserveSnapshot;
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    public MavenExecutionRequest getRequest()
    {
        return request;
    }

    public void setVersioningChanges( final Map<String, String> versioningChanges )
    {
        this.versioningChanges = versioningChanges;
    }

    public Map<String, String> getVersioningChanges()
    {
        return versioningChanges;
    }

    public Set<String> getChangedGAVs()
    {
        return changedGAVs;
    }

    public void setRepositorySystemSession( final RepositorySystemSession repositorySession )
    {
        this.repositorySession = repositorySession;
    }

    public RepositorySystemSession getRepositorySystemSession()
    {
        return repositorySession;
    }

}
