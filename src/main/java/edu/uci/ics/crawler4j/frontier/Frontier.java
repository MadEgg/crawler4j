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

package edu.uci.ics.crawler4j.frontier;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import edu.uci.ics.crawler4j.crawler.Configurable;
import edu.uci.ics.crawler4j.crawler.CrawlConfig;
import edu.uci.ics.crawler4j.frontier.Counters.ReservedCounterNames;
import edu.uci.ics.crawler4j.url.WebURL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author Yasser Ganjisaffar
 */

public class Frontier extends Configurable {
  /** Identifier for the InProgress queue: pages in progress by a thread */
  public static final int IN_PROGRESS_QUEUE = 1;
  /** Identifier for the WorkQueue: pages not yet claimed by any thread */
  public static final int WORK_QUEUE = 2;
  /** convenience identifier for both queues: IN_PROGRESS_QUEUE | WORK_QUEUE */
  public static final int BOTH_QUEUES = IN_PROGRESS_QUEUE | WORK_QUEUE;
  
  protected static final Logger logger = LoggerFactory.getLogger(Frontier.class);

  protected WorkQueues workQueues;

  protected InProcessPagesDB inProcessPages;

  protected final Object mutex = new Object();
  protected final Object waitingList = new Object();

  protected boolean isFinished = false;

  protected long scheduledPages;

  protected DocIDServer docIdServer;

  protected Counters counters;

  public Frontier(Environment env, CrawlConfig config, DocIDServer docIdServer) {
    super(config);
    this.counters = new Counters(env, config);
    this.docIdServer = docIdServer;
    try {
      workQueues = new WorkQueues(env, "PendingURLsDB", config.isResumableCrawling());
      scheduledPages = counters.getValue(ReservedCounterNames.SCHEDULED_PAGES);
      inProcessPages = new InProcessPagesDB(env, config.isResumableCrawling());
      long numPreviouslyInProcessPages = inProcessPages.getLength();
      if (numPreviouslyInProcessPages > 0) {
        logger.info("Rescheduling {} URLs from previous crawl.", numPreviouslyInProcessPages);
        scheduledPages -= numPreviouslyInProcessPages;
        while (true) {
          List<WebURL> urls = inProcessPages.shift(100);
          if (urls.size() == 0) {
            break;
          }
          scheduleAll(urls);
        }
      }
    } catch (DatabaseException e) {
      logger.error("Error while initializing the Frontier: {}", e.getMessage());
      workQueues = null;
    }
  }

  public void scheduleAll(List<WebURL> urls) {
    int maxPagesToFetch = config.getMaxPagesToFetch();
    synchronized (mutex) {
      int newScheduledPage = 0;
      for (WebURL url : urls) {
        if (maxPagesToFetch > 0 && (scheduledPages + newScheduledPage) >= maxPagesToFetch) {
          break;
        }
        try {
          workQueues.put(url);
          newScheduledPage++;
        } catch (DatabaseException e) {
          logger.error("Error while putting the url in the work queue.");
        }
      }
      if (newScheduledPage > 0) {
        scheduledPages += newScheduledPage;
        counters.increment(Counters.ReservedCounterNames.SCHEDULED_PAGES, newScheduledPage);
      }
      synchronized (waitingList) {
        waitingList.notifyAll();
      }
    }
  }

  public void schedule(WebURL url) {
    int maxPagesToFetch = config.getMaxPagesToFetch();
    synchronized (mutex) {
      try {
        if (maxPagesToFetch < 0 || scheduledPages < maxPagesToFetch) {
          workQueues.put(url);
          scheduledPages++;
          counters.increment(Counters.ReservedCounterNames.SCHEDULED_PAGES);
        }
      } catch (DatabaseException e) {
        logger.error("Error while putting the url in the work queue.");
      }
    }
    
    // Wake up threads
    synchronized (waitingList) {
      waitingList.notifyAll();
    }
  }
  
  /**
   * Remove all document IDs from the DocIDServer. This allows to re-crawl
   * pages that have been visited before, which can be useful in a long-running
   * crawler that may revisit pages after a certain amount of time.
   * 
   * This method will wait until all queues are empty to avoid purging DocIDs
   * that are still will be crawled before actually clearing the database, so make
   * sure the crawler is running when executing this method.
   */
  public void clearDocIDs() {
    while (true) {
      if (getQueueLength() > 0) {
        synchronized (waitingList) {
          try {
            waitingList.wait(2000);
          } catch (InterruptedException e)
          {}
        }
      } else {
        synchronized (mutex) {
          if (getQueueLength() > 0)
            continue;
          docIdServer.clear();
          logger.info("Document ID Server has been emptied.");
          break;
        }
      }
    }
  }

  public void getNextURLs(int max, List<WebURL> result) {
    while (true) {
      synchronized (mutex) {
        if (isFinished) {
          return;
        }
        try {
          List<WebURL> curResults = workQueues.shift(max);
          for (WebURL curPage : curResults) {
            if (inProcessPages.put(curPage))
                result.add(curPage);
          }
        } catch (DatabaseException e) {
          logger.error("Error while getting next urls: {}", e.getMessage());
          e.printStackTrace();
        }
        if (result.size() > 0) {
          return;
        }
      }
      synchronized (waitingList) {
        try {
          waitingList.wait();
        } catch (InterruptedException e)
        {}
      }
    }
  }

  /**
   * Set the page as processed and return true if, as a consequence, there is no
   * more offspring left of the seed that eventually resulted in this document.
   * 
   * @param webURL The URL to set as processed
   * @return True when this was the last offspring of the seed, false otherwise
   */
  public boolean setProcessed(WebURL webURL) {
    counters.increment(ReservedCounterNames.PROCESSED_PAGES);
    synchronized (mutex) {
      if (!inProcessPages.removeURL(webURL)) {
        logger.warn("Could not remove: {} from list of processed pages.", webURL.getURL());
      }
      return numOffspring(webURL.getSeedDocid()) == 0;
    }
  }
  
  public long getQueueLength() {
    return getQueueLength(WORK_QUEUE & IN_PROGRESS_QUEUE);
  }

  public long getQueueLength(int type) {
    synchronized (mutex) {
      int length = 0;
      if ((type & WORK_QUEUE) == WORK_QUEUE)
          length += workQueues.getLength();
      if ((type & IN_PROGRESS_QUEUE) == IN_PROGRESS_QUEUE)
          length += inProcessPages.getLength();
      return length;
    }
  }
  
  public int numOffspring(Integer seedDocid) {
    synchronized (mutex) {
      return workQueues.getSeedCount(seedDocid) + inProcessPages.getSeedCount(seedDocid);
    }
  }
  
  public long getNumberOfAssignedPages() {
    return inProcessPages.getLength();
  }

  public long getNumberOfProcessedPages() {
    return counters.getValue(ReservedCounterNames.PROCESSED_PAGES);
  }

  public void sync() {
    workQueues.sync();
    docIdServer.sync();
    counters.sync();
    inProcessPages.sync();
  }

  public boolean isFinished() {
    return isFinished;
  }

  public void close() {
    sync();
    workQueues.close();
    counters.close();
    inProcessPages.close();
  }

  public void finish() {
    if (!isFinished) {
      isFinished = true;
      synchronized (waitingList) {
        waitingList.notifyAll();
      }
    }
  }
  
  /**
   * Allow a certain piece of code to be run synchronously. This method
   * acquires the mutex and then runs the run method in the provided runnable.
   * 
   * @param r The object on which to run the run method synchronized
   */
  public void runSync(Runnable r) {
      synchronized (mutex) {
          r.run();
      }
  }
}
