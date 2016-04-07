/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.uci.ics.crawler4j.crawler;

import java.io.File;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;

import edu.uci.ics.crawler4j.fetcher.PageFetcher;
import edu.uci.ics.crawler4j.frontier.DocIDServer;
import edu.uci.ics.crawler4j.frontier.Frontier;
import edu.uci.ics.crawler4j.robotstxt.RobotstxtServer;
import edu.uci.ics.crawler4j.url.TLDList;
import edu.uci.ics.crawler4j.url.URLCanonicalizer;
import edu.uci.ics.crawler4j.url.WebURL;
import edu.uci.ics.crawler4j.util.IO;

/**
 * The controller that manages a crawling session. This class creates the
 * crawler threads and monitors their progress.
 * 
 * @author Yasser Ganjisaffar
 */
public class CrawlController extends Configurable {
  static final Logger logger = LoggerFactory.getLogger(CrawlController.class);

  /** The monitor thread self */
  protected Thread monitorThread = null;
  
  /** The list of monitored threads by the CrawlController */
  protected final List<Thread> threads = new ArrayList<>();
  
  /**
   * The 'customData' object can be used for passing custom crawl-related
   * configurations to different components of the crawler.
   */
  protected Object customData;

  /**
   * Once the crawling session finishes the controller collects the local data
   * of the crawler threads and stores them in this List.
   */
  protected List<Object> crawlersLocalData = new ArrayList<>();

  /**
   * Is the crawling of this session finished?
   */
  protected boolean finished;

  /**
   * Is the crawling session set to 'shutdown'. Crawler threads monitor this
   * flag and when it is set they will no longer process new pages.
   */
  protected boolean shuttingDown;

  protected PageFetcher pageFetcher;
  protected RobotstxtServer robotstxtServer;
  protected Frontier frontier;
  protected DocIDServer docIdServer;

  protected final Object waitingLock = new Object();
  protected final Environment env;

  public CrawlController(CrawlConfig config, PageFetcher pageFetcher, RobotstxtServer robotstxtServer)
      throws Exception {
    super(config);

    config.validate();
    File folder = new File(config.getCrawlStorageFolder());
    if (!folder.exists()) {
      if (folder.mkdirs()) {
        logger.debug("Created folder: " + folder.getAbsolutePath());
      } else {
        throw new Exception(
            "couldn't create the storage folder: " + folder.getAbsolutePath() + " does it already exist ?");
      }
    }

    TLDList.setUseOnline(config.isOnlineTldListUpdate());

    boolean resumable = config.isResumableCrawling();

    EnvironmentConfig envConfig = new EnvironmentConfig();
    envConfig.setAllowCreate(true);
    envConfig.setTransactional(resumable);
    envConfig.setLocking(resumable);

    File envHome = new File(config.getCrawlStorageFolder() + "/frontier");
    if (!envHome.exists()) {
      if (envHome.mkdir()) {
        logger.debug("Created folder: " + envHome.getAbsolutePath());
      } else {
        throw new Exception("Failed creating the frontier folder: " + envHome.getAbsolutePath());
      }
    }

    if (!resumable) {
      IO.deleteFolderContents(envHome);
      logger.info("Deleted contents of: " + envHome + " ( as you have configured resumable crawling to false )");
    }

    env = new Environment(envHome, envConfig);
    docIdServer = new DocIDServer(env, config);
    frontier = new Frontier(env, config, docIdServer);

    this.pageFetcher = pageFetcher;
    this.robotstxtServer = robotstxtServer;

    finished = false;
    shuttingDown = false;
  }

  public interface WebCrawlerFactory<T extends WebCrawler> {
    T newInstance() throws Exception;
  }

  private static class DefaultWebCrawlerFactory<T extends WebCrawler> implements WebCrawlerFactory<T> {
    final Class<T> _c;

    DefaultWebCrawlerFactory(Class<T> _c) {
      this._c = _c;
    }

    @Override
    public T newInstance() throws Exception {
      try {
        return _c.newInstance();
      } catch (ReflectiveOperationException e) {
        throw e;
      }
    }
  }

  /**
   * Start the crawling session and wait for it to finish.
   * This method utilizes default crawler factory that creates new crawler using Java reflection
   *
   * @param _c
   *            the class that implements the logic for crawler threads
   * @param numberOfCrawlers
   *            the number of concurrent threads that will be contributing in
   *            this crawling session.
   * @param <T> Your class extending WebCrawler
   */
  public <T extends WebCrawler> void start(final Class<T> _c, final int numberOfCrawlers) {
    this.start(new DefaultWebCrawlerFactory<>(_c), numberOfCrawlers, true);
  }

  /**
   * Start the crawling session and wait for it to finish.
   *
   * @param crawlerFactory
   *            factory to create crawlers on demand for each thread
   * @param numberOfCrawlers
   *            the number of concurrent threads that will be contributing in
   *            this crawling session.
   * @param <T> Your class extending WebCrawler
   */
  public <T extends WebCrawler> void start(final WebCrawlerFactory<T> crawlerFactory, final int numberOfCrawlers) {
    this.start(crawlerFactory, numberOfCrawlers, true);
  }

  /**
   * Start the crawling session and return immediately.
   *
   * @param crawlerFactory
   *            factory to create crawlers on demand for each thread
   * @param numberOfCrawlers
   *            the number of concurrent threads that will be contributing in
   *            this crawling session.
   * @param <T> Your class extending WebCrawler
   */
  public <T extends WebCrawler> void startNonBlocking(WebCrawlerFactory<T> crawlerFactory, final int numberOfCrawlers) {
    this.start(crawlerFactory, numberOfCrawlers, false);
  }

  /**
   * Start the crawling session and return immediately.
   * This method utilizes default crawler factory that creates new crawler using Java reflection
   *
   * @param _c
   *            the class that implements the logic for crawler threads
   * @param numberOfCrawlers
   *            the number of concurrent threads that will be contributing in
   *            this crawling session.
   * @param <T> Your class extending WebCrawler
   */
  public <T extends WebCrawler> void startNonBlocking(final Class<T> _c, final int numberOfCrawlers) {
    this.start(new DefaultWebCrawlerFactory<>(_c), numberOfCrawlers, false);
  }

  protected <T extends WebCrawler> void start(final WebCrawlerFactory<T> crawlerFactory, final int numberOfCrawlers, boolean isBlocking) {
    try {
      finished = false;
      crawlersLocalData.clear();
      final List<T> crawlers = new ArrayList<>();

      for (int i = 1; i <= numberOfCrawlers; i++) {
        T crawler = crawlerFactory.newInstance();
        Thread thread = new Thread(crawler, "Crawler " + i);
        crawler.setThread(thread);
        crawler.init(i, this);
        thread.start();
        crawlers.add(crawler);
        threads.add(thread);
        logger.info("Crawler {} started", i);
      }

      final CrawlController controller = this;

      if (monitorThread != null) {
        logger.error("MonitorThread is already started.");
        return;
      }
      
      monitorThread = new Thread(new Runnable() {

        @Override
        public void run() {
          try {
            synchronized (waitingLock) {

              while (true) {
                sleep(1);
                for (int i = 0; i < threads.size(); i++) {
                  Thread thread = threads.get(i);
                  if (!thread.isAlive()) {
                    if (!shuttingDown) {
                      logger.info("Thread {} was dead, I'll recreate it", i);
                      T crawler = crawlerFactory.newInstance();
                      thread = new Thread(crawler, "Crawler " + (i + 1));
                      threads.remove(i);
                      threads.add(i, thread);
                      T oldCrawler = crawlers.get(i);
                      crawler.setThread(thread);
                      crawler.init(i + 1, controller);
                      crawler.resume(oldCrawler.extractAssignedURL());
                      thread.start();
                      crawlers.remove(i);
                      crawlers.add(i, crawler);
                    }
                  } 
                }
                boolean shut_on_empty = config.isShutdownOnEmptyQueue();
                if (shuttingDown || (frontier.getQueueLength() == 0 && shut_on_empty)) {
                  if (!shuttingDown)
                  {
                    logger.info("No pages are in progress and none are enqueued. Waiting a second to make sure");
                    sleep(1);
                    if (frontier.getQueueLength() > 0)
                      continue;
                    logger.info("Still no pages are in progress and still none are enqueued. Finishing the process...");
                    shuttingDown = true;
                  }
                  
                  // At this step, frontier notifies the
                  // threads that were
                  // waiting for new URLs and they should
                  // stop
                  frontier.finish();
                  for (T crawler : crawlers) {
                    crawler.onBeforeExit();
                    crawlersLocalData.add(crawler.getMyLocalData());
                  }
                  
                  logger.info("Joining all running threads...");
                  for (Thread t : threads)
                      t.join();
                  frontier.close();
                  docIdServer.close();
                  pageFetcher.shutDown();

                  finished = true;
                  waitingLock.notifyAll();
                  env.close();
                  return;
                }
              }
            }
          } catch (Exception e) {
            logger.error("Unexpected Error", e);
          }
        }
      });

      monitorThread.start();

      if (isBlocking) {
        waitUntilFinish();
      }

    } catch (Exception e) {
      logger.error("Error happened", e);
    }
  }

  /**
   * Wait until this crawling session finishes.
   */
  // Deprecation warning suppressed because it results from the use of the Thread.stop 
  // method. This method is used as a final attempt to shutdown in case of an OutOfMemory
  // exception, so we don't care about negative side effects.
@SuppressWarnings("deprecation")
public void waitUntilFinish() {
    while (!finished) {
      if (monitorThread == null || !monitorThread.isAlive()) {
        logger.warn("Monitor thread is dead, but finished was not set. Something went wrong.");
        return;
      }
          
      if (finished) {
        return;
      }
      
      synchronized (waitingLock) {
        try {
          waitingLock.wait(1000);
        } catch (InterruptedException e) {
          logger.error("Error occurred", e);
        }
      }
      
      // Catch errors by checking threads and monitor thread now
      boolean threads_alive = false;
      for (Thread t : threads)
          if (t.isAlive())
              threads_alive = true;
      
      if (!threads_alive)
      {
        if (monitorThread != null && monitorThread.isAlive()) {
          monitorThread.stop(new RuntimeException("Monitor thread is refusing to shut down"));
          return;
        }
      }
    }
  }

  /**
   * Once the crawling session finishes the controller collects the local data of the crawler threads and stores them
   * in a List.
   * This function returns the reference to this list.
   *
   * @return List of Objects which are your local data
   */
  public List<Object> getCrawlersLocalData() {
    return crawlersLocalData;
  }

  protected static void sleep(int seconds) {
    try {
      Thread.sleep(seconds * 1000);
    } catch (InterruptedException ignored) {
      // Do nothing
    }
  }

  /**
   * Adds a new seed URL. A seed URL is a URL that is fetched by the crawler
   * to extract new URLs in it and follow them for crawling.
   *
   * @param pageUrl
   *            the URL of the seed
   * @throws URISyntaxException invalid page url
   * @return The doc id assigned to theURL
   */
  public long addSeed(String pageUrl) throws URISyntaxException {
    return addSeed(pageUrl, -1);
  }

  /**
   * Adds a new seed URL with a priority of 0. A seed URL is a URL that is fetched
   * by the crawler to extract new URLs in it and follow them for crawling.
   * 
   * @see #addSeed(String, long, byte)
   * 
   * @param pageUrl
   *            the URL of the seed
   * @param docId
   *            the document id that you want to be assigned to this seed URL.
   * @throws URISyntaxException on invalid page url
   * @return The doc ID assigned to the url
   */
  public long addSeed(String pageUrl, long docId) throws URISyntaxException {
    return addSeed(pageUrl, docId, (byte)0);
  }
    
  /**
   * Adds a new seed URL. A seed URL is a URL that is fetched by the crawler
   * to extract new URLs in it and follow them for crawling. You can also
   * specify a specific document id to be assigned to this seed URL. This
   * document id needs to be unique. Also, note that if you add three seeds
   * with document ids 1,2, and 7. Then the next URL that is found during the
   * crawl will get a doc id of 8. Also you need to ensure to add seeds in
   * increasing order of document ids.
   *
   * Specifying doc ids is mainly useful when you have had a previous crawl
   * and have stored the results and want to start a new crawl with seeds
   * which get the same document ids as the previous crawl.
   *
   * @param pageUrl
   *            the URL of the seed
   * @param docId
   *            the document id that you want to be assigned to this seed URL.
   * @param priority
   *            the priority to assign to this seed
   * @throws URISyntaxException on invalid page url
   * @return The docId used / assigned for the seed URL
   */
  public long addSeed(String pageUrl, long docId, byte priority) throws URISyntaxException {
    String canonicalUrl = URLCanonicalizer.getCanonicalURL(pageUrl);
    if (canonicalUrl == null) {
      logger.error("Invalid seed URL: {}", pageUrl);
      return -1;
    }
    WebURL webUrl = new WebURL(canonicalUrl);
    webUrl.setSeedDocid(docId);
    webUrl.setDepth((short) 0);
    webUrl.setPriority(priority);
    if (docId >= 0)
      webUrl.setDocid(docId);
    if (!config.isIgnoreRobotsTxtForSeed() && !robotstxtServer.allows(webUrl)) {
      // using the WARN level here, as the user specifically asked to add this seed
      logger.warn("Robots.txt does not allow this seed: {}", pageUrl);
    } else {
      frontier.schedule(webUrl);
    }
    return webUrl.getDocid();
  }

  /**
   * This function can called to assign a specific document id to a url. This
   * feature is useful when you have had a previous crawl and have stored the
   * Urls and their associated document ids and want to have a new crawl which
   * is aware of the previously seen Urls and won't re-crawl them.
   *
   * Note that if you add three seen Urls with document ids 1,2, and 7. Then
   * the next URL that is found during the crawl will get a doc id of 8. Also
   * you need to ensure to add seen Urls in increasing order of document ids.
   *
   * @param url
   *            the URL of the page
   * @param docId
   *            the document id that you want to be assigned to this URL.
   *
   */
  public void addSeenUrl(String url, int docId) {
    String canonicalUrl = URLCanonicalizer.getCanonicalURL(url);
    if (canonicalUrl == null) {
      logger.error("Invalid Url: {} (can't cannonicalize it!)", url);
    } else {
      try {
        docIdServer.addUrlAndDocId(canonicalUrl, docId);
      } catch (Exception e) {
        logger.error("Could not add seen url: {}", e.getMessage());
      }
    }
  }

  public PageFetcher getPageFetcher() {
    return pageFetcher;
  }

  public void setPageFetcher(PageFetcher pageFetcher) {
    this.pageFetcher = pageFetcher;
  }

  public RobotstxtServer getRobotstxtServer() {
    return robotstxtServer;
  }

  public void setRobotstxtServer(RobotstxtServer robotstxtServer) {
    this.robotstxtServer = robotstxtServer;
  }

  public Frontier getFrontier() {
    return frontier;
  }

  public void setFrontier(Frontier frontier) {
    this.frontier = frontier;
  }

  public DocIDServer getDocIdServer() {
    return docIdServer;
  }

  public void setDocIdServer(DocIDServer docIdServer) {
    this.docIdServer = docIdServer;
  }

  public Object getCustomData() {
    return customData;
  }

  public void setCustomData(Object customData) {
    this.customData = customData;
  }

  public boolean isFinished() {
    return this.finished;
  }

  public boolean isShuttingDown() {
    return shuttingDown;
  }

  /**
   * Set the current crawling session set to 'shutdown'. Crawler threads
   * monitor the shutdown flag and when it is set to true, they will no longer
   * process new pages.
   */
  public void shutdown() {
    logger.info("Shutting down...");
    this.shuttingDown = true;
    pageFetcher.shutDown();
    frontier.finish();
  }
}
