# GitLab Wiki Synchronization Guide

## Overview

This guide shows how to sync a GitLab wiki (`.md` and `.adoc` files) with LightRAG for semantic search.

**Good news:** GitLab wikis are git repositories, making this MUCH simpler than web scraping!

---

## Why GitLab Wiki is Easy

✅ **Git-based**: Clone the wiki repository
✅ **Already markdown**: `.md` and `.adoc` files (no HTML parsing needed)
✅ **Change detection**: Git commits tell you what changed
✅ **API access**: GitLab REST API for metadata
✅ **No scraping**: Direct file system access

---

## Current LightRAG Capabilities

### What LightRAG Provides

✅ **Upload API** (`POST /documents/upload`)
- Accepts multipart file uploads
- Supports Markdown (.md) and AsciiDoc (.adoc) natively
- Async processing with job IDs
- Workspace isolation

✅ **Ingest API** (`POST /documents/ingest`)
- Direct JSON ingestion
- Structured document format

❌ **What's NOT Built-In**
- Git repository cloning
- Scheduled synchronization
- GitLab API integration

**Conclusion:** You need a simple sync component (much easier than web scraping!).

---

## Recommended Architecture for GitLab Wiki

```
┌──────────────────┐
│   GitLab Server  │
│   Wiki Git Repo  │
└────────┬─────────┘
         │
         │ (1) git clone / pull
         ▼
┌──────────────────┐
│  Local Git Clone │
│   ├── page1.md   │
│   ├── page2.md   │
│   └── docs/      │
│       └──file.adoc
└────────┬─────────┘
         │
         │ (2) Find changed files (git diff)
         │ (3) Read .md / .adoc files
         │ (4) Upload to API
         ▼
┌─────────────────┐
│   LightRAG API  │
│  /documents/*   │
└────────┬────────┘
         │
         │ (5) Process & Index
         ▼
┌─────────────────┐
│   PostgreSQL    │
│   + Neo4j       │
└─────────────────┘
```

**Key differences from web scraping:**
- ✅ No HTML parsing (files are already .md / .adoc)
- ✅ Git handles change detection
- ✅ Faster (local file system, not HTTP)
- ✅ Reliable (no network flakiness)

---

## Implementation Options

### Option 1: JGit (Java Git) + LightRAG API (Recommended)

**Best for:** GitLab wikis, production use, full automation

**Tech Stack:**
- **JGit**: Pure Java implementation of Git
- **OkHttp3**: HTTP client (already in LightRAG)
- **GitLab4J-API**: GitLab REST API client (optional, for metadata)
- **Quartz Scheduler**: Job scheduling

**Pros:**
- ✅ Simple (~300-500 lines of code vs 1000-2000 for web scraping)
- ✅ Git handles change detection automatically
- ✅ No HTML parsing needed (files are already .md/.adoc)
- ✅ Works offline after initial clone
- ✅ Reliable (no network flakiness during sync)

**Cons:**
- ⚠️ Requires git clone access to wiki repo
- ⚠️ Need GitLab access token

**Implementation Time:** 3-5 days (much faster than web scraping!)

---

### Option 2: Python Scraper + REST API

**Best for:** Quick prototypes, Python expertise on team

**Tech Stack:**
- **Scrapy**: Professional web scraping framework
- **Beautiful Soup**: HTML parsing
- **Requests**: HTTP library
- **Schedule** or **APScheduler**: Job scheduling

**Pros:**
- ✅ Rich ecosystem for web scraping
- ✅ Faster development (500-1000 lines)
- ✅ Many examples available

**Cons:**
- ⚠️ Requires Python runtime alongside Java
- ⚠️ Network calls to upload API
- ⚠️ Different tech stack to maintain

**Implementation Time:** 1 week

---

### Option 3: Commercial Tools (For Specific Wikis)

#### Confluence
Use **Confluence REST API** instead of scraping:
```java
// Get all pages
GET /rest/api/content?type=page&limit=100

// Get page content
GET /rest/api/content/{pageId}?expand=body.storage
```

**Pros:**
- ✅ Official API (better than scraping)
- ✅ Includes metadata (author, last modified)
- ✅ Change detection via version numbers

#### MediaWiki (Wikipedia-style)
Use **MediaWiki API**:
```
// Get all pages
GET /api.php?action=query&list=allpages

// Get page content
GET /api.php?action=parse&page=PageTitle&format=json
```

#### Other Wiki Platforms
Most modern wikis have REST APIs - check documentation first before scraping.

---

## Detailed Implementation: GitLab Wiki Sync

### 1. Dependencies (pom.xml)

```xml
<dependencies>
    <!-- JGit - Pure Java Git -->
    <dependency>
        <groupId>org.eclipse.jgit</groupId>
        <artifactId>org.eclipse.jgit</artifactId>
        <version>6.9.0.202403050737-r</version>
    </dependency>

    <!-- HTTP Client (already in LightRAG) -->
    <dependency>
        <groupId>com.squareup.okhttp3</groupId>
        <artifactId>okhttp</artifactId>
    </dependency>

    <!-- GitLab API Client (optional, for metadata) -->
    <dependency>
        <groupId>org.gitlab4j</groupId>
        <artifactId>gitlab4j-api</artifactId>
        <version>5.5.0</version>
    </dependency>

    <!-- Scheduling -->
    <dependency>
        <groupId>org.quartz-scheduler</groupId>
        <artifactId>quartz</artifactId>
        <version>2.3.2</version>
    </dependency>
</dependencies>
```

### 2. GitLab Wiki Sync Implementation

```java
package io.github.lightrag.wiki;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Scrapes wiki pages and uploads them to LightRAG
 */
public class WikiScraper {
    private final String baseUrl;
    private final String lightragApiUrl;
    private final OkHttpClient httpClient;
    private final FlexmarkHtmlConverter htmlToMarkdown;
    private final Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
    private final Map<String, String> pageHashes = new ConcurrentHashMap<>();

    public WikiScraper(String baseUrl, String lightragApiUrl) {
        this.baseUrl = baseUrl;
        this.lightragApiUrl = lightragApiUrl;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
        this.htmlToMarkdown = FlexmarkHtmlConverter.builder().build();
    }

    /**
     * Crawl wiki starting from root URL
     */
    public CrawlResult crawl(String startUrl, int maxPages) {
        Queue<String> queue = new LinkedList<>();
        queue.add(startUrl);

        int pagesProcessed = 0;
        int pagesUpdated = 0;
        int pagesSkipped = 0;

        while (!queue.isEmpty() && pagesProcessed < maxPages) {
            String url = queue.poll();

            if (visitedUrls.contains(url)) {
                continue;
            }
            visitedUrls.add(url);

            try {
                // Fetch page
                WikiPage page = fetchPage(url);

                // Check if page changed (for incremental updates)
                String currentHash = calculateHash(page.content);
                String previousHash = pageHashes.get(url);

                if (currentHash.equals(previousHash)) {
                    pagesSkipped++;
                    System.out.println("Skipped (unchanged): " + url);
                } else {
                    // Upload to LightRAG
                    uploadPage(page);
                    pageHashes.put(url, currentHash);
                    pagesUpdated++;
                    System.out.println("Uploaded: " + page.title + " (" + url + ")");
                }

                // Find and queue linked pages
                for (String link : page.links) {
                    if (isWikiPage(link) && !visitedUrls.contains(link)) {
                        queue.add(link);
                    }
                }

                pagesProcessed++;

                // Be nice to the server
                Thread.sleep(1000);

            } catch (Exception e) {
                System.err.println("Error processing " + url + ": " + e.getMessage());
            }
        }

        return new CrawlResult(pagesProcessed, pagesUpdated, pagesSkipped);
    }

    /**
     * Fetch and parse a wiki page
     */
    private WikiPage fetchPage(String url) throws IOException {
        Request request = new Request.Builder()
            .url(url)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for " + url);
            }

            String html = response.body().string();
            Document doc = Jsoup.parse(html, url);

            // Extract title
            String title = doc.title();

            // Extract main content (adjust selector for your wiki)
            Element content = doc.selectFirst("main, .wiki-content, #content, article");
            if (content == null) {
                content = doc.body();
            }

            // Remove navigation, footers, sidebars
            content.select("nav, footer, aside, .sidebar, .toc").remove();

            // Convert to markdown
            String markdown = htmlToMarkdown.convert(content.html());

            // Extract links
            Elements linkElements = content.select("a[href]");
            List<String> links = linkElements.stream()
                .map(e -> e.absUrl("href"))
                .filter(this::isWikiPage)
                .distinct()
                .toList();

            // Extract metadata
            Map<String, String> metadata = new HashMap<>();
            metadata.put("sourceUrl", url);
            metadata.put("scrapedAt", new Date().toString());

            // Try to extract last modified date
            Element modifiedElement = doc.selectFirst(".last-modified, .modified-date");
            if (modifiedElement != null) {
                metadata.put("lastModified", modifiedElement.text());
            }

            return new WikiPage(url, title, markdown, links, metadata);
        }
    }

    /**
     * Upload page to LightRAG
     */
    private void uploadPage(WikiPage page) throws IOException {
        // Convert to multipart form data
        MultipartBody.Builder formBuilder = new MultipartBody.Builder()
            .setType(MultipartBody.FORM);

        // Add file content
        byte[] contentBytes = page.content.getBytes(StandardCharsets.UTF_8);
        formBuilder.addFormDataPart(
            "files",
            sanitizeFileName(page.title) + ".md",
            RequestBody.create(contentBytes, MediaType.parse("text/markdown"))
        );

        RequestBody body = formBuilder.build();

        Request request = new Request.Builder()
            .url(lightragApiUrl + "/documents/upload")
            .post(body)
            .addHeader("X-Workspace-Id", "corporate-wiki")
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Upload failed: " + response.code());
            }
            // Could parse response to get jobId if needed
        }
    }

    /**
     * Check if URL is a wiki page (not external, not asset)
     */
    private boolean isWikiPage(String url) {
        try {
            URL parsed = new URL(url);
            String host = parsed.getHost();
            String path = parsed.getPath();

            // Same host
            if (!url.startsWith(baseUrl)) {
                return false;
            }

            // Not an asset
            if (path.matches(".*\\.(css|js|jpg|png|gif|ico|svg|pdf|zip)$")) {
                return false;
            }

            // Not special pages
            if (path.contains("/api/") || path.contains("/login") || path.contains("/logout")) {
                return false;
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Calculate content hash for change detection
     */
    private String calculateHash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            return "";
        }
    }

    private String sanitizeFileName(String title) {
        return title.replaceAll("[^a-zA-Z0-9-_]", "_");
    }

    record WikiPage(
        String url,
        String title,
        String content,
        List<String> links,
        Map<String, String> metadata
    ) {}

    record CrawlResult(int processed, int updated, int skipped) {}
}
```

### 3. Scheduled Scraper Job

```java
package io.github.lightrag.wiki;

import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

/**
 * Schedules periodic wiki scraping
 */
public class WikiSyncScheduler {

    public static void main(String[] args) throws Exception {
        // Create scheduler
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();

        // Define job
        JobDetail job = JobBuilder.newJob(WikiSyncJob.class)
            .withIdentity("wikiSync", "scraping")
            .build();

        // Trigger: Run daily at 2 AM
        Trigger trigger = TriggerBuilder.newTrigger()
            .withIdentity("dailySync", "scraping")
            .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(2, 0))
            .build();

        // Schedule job
        scheduler.scheduleJob(job, trigger);
        scheduler.start();

        System.out.println("Wiki sync scheduler started. Press Ctrl+C to stop.");
        Thread.sleep(Long.MAX_VALUE);
    }

    public static class WikiSyncJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
            System.out.println("Starting wiki sync at " + new java.util.Date());

            WikiScraper scraper = new WikiScraper(
                "https://wiki.company.com",
                "http://localhost:8080"
            );

            try {
                WikiScraper.CrawlResult result = scraper.crawl(
                    "https://wiki.company.com/index.html",
                    10000  // Max pages
                );

                System.out.println("Sync complete:");
                System.out.println("  Processed: " + result.processed());
                System.out.println("  Updated: " + result.updated());
                System.out.println("  Skipped: " + result.skipped());

            } catch (Exception e) {
                System.err.println("Sync failed: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
```

### 4. Configuration (application.yml)

```yaml
wiki:
  scraper:
    base-url: https://wiki.company.com
    start-url: https://wiki.company.com/index.html
    max-pages: 10000
    rate-limit-ms: 1000  # Delay between requests
    user-agent: "CompanyWikiScraper/1.0"

  sync:
    schedule: "0 0 2 * * ?"  # Daily at 2 AM
    enabled: true

  lightrag:
    api-url: http://localhost:8080
    workspace-id: corporate-wiki
```

---

## Advanced Features

### 1. Sitemap-Based Scraping (Faster)

```java
/**
 * Use sitemap.xml instead of crawling
 */
public List<String> getPagesFromSitemap(String sitemapUrl) throws IOException {
    Request request = new Request.Builder().url(sitemapUrl).build();

    try (Response response = httpClient.newCall(request).execute()) {
        String xml = response.body().string();
        Document doc = Jsoup.parse(xml, "", Parser.xmlParser());

        return doc.select("url > loc").stream()
            .map(Element::text)
            .filter(this::isWikiPage)
            .toList();
    }
}

// Usage
List<String> allPages = scraper.getPagesFromSitemap("https://wiki.company.com/sitemap.xml");
for (String url : allPages) {
    WikiPage page = scraper.fetchPage(url);
    scraper.uploadPage(page);
}
```

### 2. Change Detection via ETags/Last-Modified

```java
/**
 * Use HTTP headers for efficient change detection
 */
private boolean hasPageChanged(String url, String lastETag) throws IOException {
    Request request = new Request.Builder()
        .url(url)
        .header("If-None-Match", lastETag)
        .build();

    try (Response response = httpClient.newCall(request).execute()) {
        return response.code() != 304;  // 304 = Not Modified
    }
}
```

### 3. Parallel Scraping (Faster)

```java
/**
 * Use thread pool for parallel scraping
 */
ExecutorService executor = Executors.newFixedThreadPool(10);
List<Future<WikiPage>> futures = new ArrayList<>();

for (String url : urls) {
    futures.add(executor.submit(() -> fetchPage(url)));
}

for (Future<WikiPage> future : futures) {
    WikiPage page = future.get();
    uploadPage(page);
}

executor.shutdown();
```

### 4. Authentication (If Required)

```java
/**
 * Add authentication for private wikis
 */
OkHttpClient authenticatedClient = new OkHttpClient.Builder()
    .addInterceptor(chain -> {
        Request original = chain.request();
        Request authenticated = original.newBuilder()
            .header("Authorization", "Bearer " + getAccessToken())
            .build();
        return chain.proceed(authenticated);
    })
    .build();
```

---

## Best Practices

### 1. Document ID Strategy

Use **URL-based stable IDs**:
```java
// Bad: Random UUID (breaks on re-scrape)
String docId = UUID.randomUUID().toString();

// Good: URL-based (stable across scrapes)
String docId = calculateHash(page.url);
```

### 2. Metadata to Include

```java
Map<String, String> metadata = new HashMap<>();
metadata.put("sourceUrl", url);
metadata.put("scrapedAt", timestamp);
metadata.put("wikiSection", extractSection(url));
metadata.put("author", extractAuthor(doc));
metadata.put("lastModified", extractModifiedDate(doc));
metadata.put("pageVersion", extractVersion(doc));
```

### 3. Error Handling

```java
try {
    WikiPage page = fetchPage(url);
    uploadPage(page);
} catch (IOException e) {
    // Retry with exponential backoff
    retryWithBackoff(() -> uploadPage(page), 3);
} catch (Exception e) {
    // Log and continue (don't fail entire crawl)
    logger.error("Failed to process " + url, e);
}
```

### 4. Rate Limiting

```java
// Respect robots.txt
RobotRules rules = parseRobotsTxt(baseUrl + "/robots.txt");
long crawlDelay = rules.getCrawlDelay();

// Add delay between requests
Thread.sleep(Math.max(crawlDelay, 1000));
```

### 5. Deletion Handling

```java
// Track all scraped URLs
Set<String> currentUrls = new HashSet<>();
currentUrls.add(page.url);

// After crawl, find deleted pages
Set<String> deletedUrls = new HashSet<>(previousUrls);
deletedUrls.removeAll(currentUrls);

// Delete from LightRAG
for (String url : deletedUrls) {
    String docId = calculateHash(url);
    deleteDocument(docId);
}
```

---

## Monitoring & Observability

### Metrics to Track

```java
// Scraping metrics
metrics.counter("wiki.pages.scraped").increment();
metrics.counter("wiki.pages.updated").increment();
metrics.counter("wiki.pages.skipped").increment();
metrics.counter("wiki.pages.failed").increment();
metrics.timer("wiki.page.fetch.duration").record(duration);
metrics.timer("wiki.page.upload.duration").record(duration);

// Quality metrics
metrics.gauge("wiki.pages.total", totalPages);
metrics.gauge("wiki.links.broken", brokenLinks);
metrics.gauge("wiki.content.empty", emptyPages);
```

### Logging

```java
logger.info("Crawl started: baseUrl={}, maxPages={}", baseUrl, maxPages);
logger.info("Page fetched: url={}, size={}, links={}", url, size, links.size());
logger.warn("Page fetch failed: url={}, error={}", url, e.getMessage());
logger.error("Upload failed: url={}, status={}", url, response.code());
logger.info("Crawl complete: processed={}, updated={}, duration={}ms",
    processed, updated, duration);
```

---

## Deployment Options

### Option 1: Standalone Service

```
┌──────────────────┐
│  Wiki Scraper    │
│  (Spring Boot)   │
│  - REST API      │
│  - Scheduler     │
└──────────────────┘
```

Deploy as separate microservice with its own API for triggering scrapes.

### Option 2: Integrated with LightRAG

Add scraper module to `lightrag-spring-boot-demo`:
```
lightrag-java/
├── lightrag-core/
├── lightrag-spring-boot-starter/
├── lightrag-spring-boot-demo/
└── lightrag-wiki-scraper/  ← New module
```

### Option 3: Serverless (AWS Lambda / Cloud Functions)

```java
public class WikiScraperLambda implements RequestHandler<Map<String,String>, String> {
    @Override
    public String handleRequest(Map<String,String> event, Context context) {
        WikiScraper scraper = new WikiScraper(...);
        WikiScraper.CrawlResult result = scraper.crawl(...);
        return "Processed: " + result.processed();
    }
}
```

Trigger via CloudWatch Events (cron schedule).

---

## Quick Start Guide

### 1. Add Dependencies

Copy dependencies from section above to your `pom.xml`.

### 2. Create Scraper Class

Copy `WikiScraper.java` to your project.

### 3. Customize Content Selector

Adjust Jsoup selector for your wiki's HTML structure:
```java
// Example for Confluence
Element content = doc.selectFirst("#main-content");

// Example for MediaWiki
Element content = doc.selectFirst("#mw-content-text");

// Example for custom wiki
Element content = doc.selectFirst(".article-body");
```

### 4. Run Initial Import

```java
public class InitialImport {
    public static void main(String[] args) {
        WikiScraper scraper = new WikiScraper(
            "https://wiki.company.com",
            "http://localhost:8080"
        );

        WikiScraper.CrawlResult result = scraper.crawl(
            "https://wiki.company.com/index.html",
            10000
        );

        System.out.println("Import complete: " + result);
    }
}
```

### 5. Set Up Scheduled Sync

Use Quartz or Spring @Scheduled:
```java
@Scheduled(cron = "0 0 2 * * *")  // Daily at 2 AM
public void syncWiki() {
    WikiScraper scraper = new WikiScraper(...);
    scraper.crawl(...);
}
```

---

## Recommendation for Your Use Case

**Best approach: Java Scraper + Scheduled Sync**

1. **Week 1:** Build basic scraper
   - Fetch pages with Jsoup
   - Convert HTML to Markdown
   - Upload to LightRAG API

2. **Week 2:** Add change detection
   - Hash-based comparison
   - Skip unchanged pages
   - Handle deletions

3. **Week 3:** Production hardening
   - Error handling & retries
   - Logging & monitoring
   - Rate limiting
   - Authentication

4. **Week 4:** Scheduling & deployment
   - Quartz/Spring scheduler
   - Deploy as service
   - Set up monitoring

**Total effort:** 3-4 weeks for production-ready solution

Would you like me to create a starter project structure or focus on any specific part of this implementation?
