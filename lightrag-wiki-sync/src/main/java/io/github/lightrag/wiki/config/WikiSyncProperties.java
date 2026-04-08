package io.github.lightrag.wiki.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration properties for wiki synchronization.
 *
 * <p>All values can be supplied via environment variables or application.yml.
 * The access token should always come from an environment variable, never
 * be committed to source control.
 *
 * <p>Example minimal configuration:
 * <pre>
 * wiki.sync.remote-url=https://gitlab.example.com
 * wiki.sync.project-path=mygroup/myproject
 * wiki.sync.access-token=${WIKI_ACCESS_TOKEN}
 * </pre>
 */
@ConfigurationProperties(prefix = "wiki.sync")
public class WikiSyncProperties {

    /** Whether the sync scheduler is active. Set to false to disable all scheduling. */
    private boolean enabled = true;

    /**
     * Cron expression for the scheduled sync job.
     * Default: daily at 02:00 server time.
     */
    private String schedule = "0 0 2 * * ?";

    /**
     * Base URL of the git host, without a trailing slash.
     * Example: https://gitlab.example.com or https://github.com
     */
    private String remoteUrl;

    /**
     * Project path (namespace/project), without a leading slash.
     * Example: mygroup/myproject
     */
    private String projectPath;

    /**
     * Personal access token or OAuth2 token with repository read scope.
     * Should be supplied via the WIKI_ACCESS_TOKEN environment variable.
     */
    private String accessToken;

    /**
     * Local filesystem path where the wiki repository will be cloned.
     * The directory will be created on first run.
     */
    private String localClonePath = "/tmp/lightrag-wiki-sync";

    /**
     * Base URL of the running LightRAG demo application, without a trailing slash.
     * Example: http://localhost:8080
     */
    private String lightragApiUrl = "http://localhost:8080";

    /**
     * LightRAG workspace ID to ingest wiki pages into.
     * Passed as the X-Workspace-Id header on every upload request.
     */
    private String workspaceId = "corporate-wiki";

    /**
     * When true, a full sync is triggered immediately after the application starts.
     * Useful for the very first run or for re-seeding a workspace.
     */
    private boolean syncOnStartup = false;

    /**
     * File extensions (including the leading dot) to consider as wiki pages.
     * Files with other extensions found in the repository are ignored.
     */
    private List<String> fileExtensions = List.of(".md", ".adoc");

    // -------------------------------------------------------------------------
    // Getters and setters
    // -------------------------------------------------------------------------

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSchedule() {
        return schedule;
    }

    public void setSchedule(String schedule) {
        this.schedule = schedule;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public void setRemoteUrl(String remoteUrl) {
        this.remoteUrl = remoteUrl;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getLocalClonePath() {
        return localClonePath;
    }

    public void setLocalClonePath(String localClonePath) {
        this.localClonePath = localClonePath;
    }

    public String getLightragApiUrl() {
        return lightragApiUrl;
    }

    public void setLightragApiUrl(String lightragApiUrl) {
        this.lightragApiUrl = lightragApiUrl;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public boolean isSyncOnStartup() {
        return syncOnStartup;
    }

    public void setSyncOnStartup(boolean syncOnStartup) {
        this.syncOnStartup = syncOnStartup;
    }

    public List<String> getFileExtensions() {
        return fileExtensions;
    }

    public void setFileExtensions(List<String> fileExtensions) {
        this.fileExtensions = fileExtensions;
    }

    /**
     * Constructs the Git remote URL for the wiki repository.
     * Both GitLab and GitHub append ".wiki.git" to the project URL.
     */
    public String wikiGitUrl() {
        return remoteUrl + "/" + projectPath + ".wiki.git";
    }
}
