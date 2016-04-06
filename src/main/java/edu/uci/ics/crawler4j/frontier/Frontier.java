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

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sleepycat.je.Environment;

import edu.uci.ics.crawler4j.crawler.Configurable;
import edu.uci.ics.crawler4j.crawler.CrawlConfig;
import edu.uci.ics.crawler4j.crawler.WebCrawler;
import edu.uci.ics.crawler4j.fetcher.PageFetcher;
import edu.uci.ics.crawler4j.url.WebURL;
import edu.uci.ics.crawler4j.util.IterateAction;

/**
 * @author Yasser Ganjisaffar
 */

public class Frontier extends Configurable {
  protected static final Logger logger = LoggerFactory.getLogger(Frontier.class);
  
  /** Identifier for the InProgress queue: pages in progress by a thread */
  public static final int IN_PROGRESS_QUEUE = 1;
  /** Identifier for the WorkQueue: pages not yet claimed by any thread */
  public static final int WORK_QUEUE = 2;
  /** convenience identifier for both queues: IN_PROGRESS_QUEUE | WORK_QUEUE */
  public static final int BOTH_QUEUES = IN_PROGRESS_QUEUE | WORK_QUEUE;

  protected CrawlQueue queue;

  protected final Object mutex = new Object();
  protected final Object waitingList = new Object();

  protected boolean isFinished = false;
  protected long lastSleepNotification = 0;

  protected long scheduledPages;

  protected DocIDServer docIdServer;
  
  protected Counters counters;
  
  /** An ordered list of the top of the work queue, sorted by priority and docid */
  protected Set<WebURL> current_queue = new TreeSet<WebURL>();
  
  /** A list of seeds that have finished, and so their offspring should be skipped */
  protected Set<Long> finished_seeds = new HashSet<Long>();

  public Frontier(Environment env, CrawlConfig config, DocIDServer docIdServer) {
    super(config);
    this.counters = new Counters(env, config);
    this.docIdServer = docIdServer;
    this.queue = new BerkeleyDBQueue(env);
    this.queue.setCrawlConfiguration(config);
    
    scheduledPages = counters.getValue(Counters.ReservedCounterNames.SCHEDULED_PAGES);
  }

  /**
   * Schedule a list of URLs at once, trying to minimize synchronization overhead.
   * 
   * @param urls The list of URLs to schedule
   */
  public void scheduleAll(List<WebURL> urls) {
    synchronized (mutex) {
      List<WebURL> rejects = queue.enqueue(urls);
      scheduledPages += (urls.size() - rejects.size());
    }
    
    counters.setValue(Counters.ReservedCounterNames.SCHEDULED_PAGES, scheduledPages);
    
    synchronized (waitingList) {
      waitingList.notifyAll();
    }
  }
  
  /**
   * Schedule a WebURL. It will use doSchedule to schedule it and update the counter values.
   * 
   * @param url The WebURL to schedule.
   * @see #doSchedule(WebURL url)
   * @return If the URL was scheduled
   */
  public boolean schedule(WebURL url) {
    boolean scheduled = false;
    try
    {
      synchronized (mutex) {
        queue.enqueue(url);
        ++scheduledPages;
        scheduled = true;
      }
      counters.increment(Counters.ReservedCounterNames.SCHEDULED_PAGES);
    } catch (RuntimeException e) {
      logger.warn("URL {} was not enqueued: {}", url.getURL(), e.getMessage());
    }
    
    // Wake up threads
    synchronized (waitingList) {
      waitingList.notifyAll();
    }
    return scheduled;
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
  
  /** 
   * Remove all URls from a specific host from the docidserver. This 
   * will enable them to be crawled again, if they are added to the queue again.
   * 
   * @param host The host to remove
   */
  public void removeHostDocids(String host)
  {
    final String host_to_remove = host.toLowerCase();
    docIdServer.iterate(new Function<String, IterateAction>() {
      public IterateAction apply(String url) {
        try {
          URL cur_url = new URL(url);
          String cur_host = cur_url.getHost().toLowerCase();
          if (cur_host.equals(host_to_remove))
            return IterateAction.REMOVE;
        }
        catch (MalformedURLException e)
        {
          // We don't want any malformed URLs in there. It shouldn't have
          // happened in th first place, but clean it up now anyway.
          logger.error("Invalid URL in the DocIDServer: {}", url);
          return IterateAction.REMOVE;
        }
        return IterateAction.CONTINUE;
      }
    });
  }
  
  /**
   * Add a seed docid that has finished. This is used to determine
   * whether upcoming URLs still need to be crawled. This could be
   * used to abort a seed when it has finished to waste as little time
   * on it as possible.
   * 
   * If the seed doc ID has no offspring in the queue, nothing happens.
   * 
   * @param seed_doc_id The docid of the seed URL to mark as finished.
   */
  public void setSeedFinished(long seed_doc_id) {
    synchronized (mutex) {
      finished_seeds.add(seed_doc_id);
      queue.removeOffspring(seed_doc_id);
    }
  }
  
  public WebURL getNextURL(WebCrawler crawler, PageFetcher pageFetcher) {
    while (true)
    {
      WebURL url;
      synchronized (mutex) {
        if (!finished_seeds.isEmpty()) {
          // Handle one ended seed if there are any
          Iterator<Long> iter = finished_seeds.iterator();
          long finished_seed = iter.next();
          long num_offspring = queue.getNumOffspring(finished_seed);
          if (num_offspring == 0) {
            crawler.handleSeedEnd(finished_seed);
            iter.remove();
          }
        }
        
        url = queue.getNextURL(crawler, pageFetcher);
      }
      
      if (url == null) {
        synchronized (waitingList) {
          try {
            waitingList.wait(config.getPolitenessDelay());
          } catch (InterruptedException e) {} // Don't care
        }
        continue;
      }
      
      // Proper URL found, go crawl it!
      return url;
    }
  }

  /**
   * Set the page as processed.
   * 
   * @param webURL The URL to set as processed
   */
  public void setProcessed(WebCrawler crawler, WebURL webURL) {
    counters.increment(Counters.ReservedCounterNames.PROCESSED_PAGES);
    synchronized (mutex) {
      queue.setFinishedURL(crawler, webURL);
      if (queue.getNumOffspring(webURL.getSeedDocid()) == 0)
        finished_seeds.add(webURL.getSeedDocid());
    }
  }

  public long numOffspring(Long seedDocid) {
    synchronized (mutex) {
      return queue.getNumOffspring(seedDocid);
    }
  }
  
  public long getQueueLength() {
    synchronized (mutex) {
      return queue.getQueueSize();
    }
  }

  public long getNumberOfAssignedPages() {
    synchronized (mutex) {
      return queue.getNumInProgress();
    }
  }

  public long getNumberOfProcessedPages() {
    return counters.getValue(Counters.ReservedCounterNames.PROCESSED_PAGES);
  }

  public boolean isFinished() {
    return isFinished;
  }

  public void close() {
    counters.close();
  }

  public void finish() {
    isFinished = true;
    synchronized (waitingList) {
      waitingList.notifyAll();
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
