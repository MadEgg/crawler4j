package edu.uci.ics.crawler4j.tests;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;

import edu.uci.ics.crawler4j.crawler.CrawlConfig;
import edu.uci.ics.crawler4j.crawler.WebCrawler;
import edu.uci.ics.crawler4j.crawler.exceptions.QueueException;
import edu.uci.ics.crawler4j.fetcher.PageFetcher;
import edu.uci.ics.crawler4j.frontier.BerkeleyDBQueue;
import edu.uci.ics.crawler4j.url.WebURL;
import edu.uci.ics.crawler4j.util.IO;
import edu.uci.ics.crawler4j.util.Util.Reference;

/**
 * Test the BerkeleyDBQueue implementation.
 * 
 * @author Egbert van der Wal
 */
public class BerkeleyDBQueueTest {
  /** The location where the queue will store its data files */
  private File test_dir;
  
  /** The queue being tested */
  private BerkeleyDBQueue queue;
  
  /** The PageFetcher instance maintaining nextFetchTime values */
  private PageFetcher fetcher;
  
  /** The generated URLs, including seeds */
  private ArrayList<WebURL> urls;
  
  /** The generated seeds */
  private ArrayList<WebURL> seeds;
  
  /** The docid counter for the queue test */
  private int next_doc_id = 1;
  
  private Throwable last_exception = null;
  
  private boolean failed = false;
  
  /** The log of actions for a specific host */
  HashMap<String, ArrayList<String> > log = new HashMap<String, ArrayList<String> >();
  
  /**
   * The setup consists of creating a default configuration, a
   * fetcher and a BerkeleyDB environment. Data will be stored
   * in a random location generated by {@link File#createTempFile(String, String)}
   * 
   * @throws IOException When the temp path cannot be created
   */
  @Before
  public void setUp() throws IOException {
    urls = new ArrayList<WebURL>();
    seeds = new ArrayList<WebURL>();
    EnvironmentConfig envConfig = new EnvironmentConfig();
    envConfig.setAllowCreate(true);
    envConfig.setTransactional(true);
    envConfig.setLocking(true);

    test_dir = File.createTempFile("queue_test", null);
    test_dir.delete();
    test_dir.mkdir();

    CrawlConfig test_config = new CrawlConfig();
    test_config.setPolitenessDelay(2000);
    test_config.setResumableCrawling(true);
    Environment test_env = new Environment(test_dir, envConfig);
    queue = new BerkeleyDBQueue(test_env, test_config);
    
    fetcher = new PageFetcher(test_config);
  }
  
  @After
  public void tearDown() throws Exception {
    System.out.println("Removing queue folder: " + test_dir);
    IO.deleteFolder(test_dir);
  }
  
  @Test
  public void testURLPriorityOrder() throws URISyntaxException, QueueException {
    WebURL seed = new WebURL("http://www.test.com/");
    seed.setDocid(1);
    seed.setSeedDocid(1);
    seed.setPriority((byte)0);
    seed.setDepth((short)0);
    assertTrue(queue.enqueue(seed));
    assertEquals(1, queue.getQueueSize());
    assertEquals(0, queue.getNumInProgress());
    
    WebURL os1 = new WebURL("http://www.test.com/1");
    os1.setDocid(2);
    os1.setSeedDocid(1);
    os1.setPriority((byte)-1);
    os1.setDepth((short)1);
    assertTrue(queue.enqueue(os1));
    assertEquals(2, queue.getQueueSize());
    assertEquals(0, queue.getNumInProgress());
    
    WebURL os2 = new WebURL("http://www.test.com/2");
    os2.setDocid(3);
    os2.setSeedDocid(1);
    os2.setPriority((byte)-2);
    os2.setDepth((short)2);
    assertTrue(queue.enqueue(os2));
    assertEquals(3, queue.getQueueSize());
    assertEquals(0, queue.getNumInProgress());
    
    WebURL os3 = new WebURL("http://www.test.com/3");
    os3.setDocid(5);
    os3.setSeedDocid(1);
    os3.setPriority((byte)1);
    os3.setDepth((short)3);
    assertTrue(queue.enqueue(os3));
    assertEquals(4, queue.getQueueSize());
    assertEquals(0, queue.getNumInProgress());
    
    WebURL os4 = new WebURL("http://www.test.com/4");
    os4.setDocid(4);
    os4.setSeedDocid(1);
    os4.setPriority((byte)1);
    os4.setDepth((short)3);
    assertTrue(queue.enqueue(os4));
    assertEquals(5, queue.getQueueSize());
    assertEquals(0, queue.getNumInProgress());
    
    Thread nt = new Thread();
    WebCrawler cw = new WebCrawler();
    cw.setThread(nt);
    WebURL sel = queue.getNextURL(cw, fetcher);
    assertNotNull(sel);
    assertEquals(os2.getDocid(), sel.getDocid());
    assertEquals(1, queue.getNumInProgress());
    assertEquals(5, queue.getQueueSize());
    
    queue.setFinishedURL(cw, sel);
    fetcher.unselect(sel);
    assertEquals(0, queue.getNumInProgress());
    assertEquals(4, queue.getQueueSize());
    
    sel = queue.getNextURL(cw, fetcher);
    assertNotNull(sel);
    assertEquals(os1.getDocid(), sel.getDocid());
    assertEquals(1, queue.getNumInProgress());
    assertEquals(4, queue.getQueueSize());
    
    queue.setFinishedURL(cw, sel);
    fetcher.unselect(sel);
    assertEquals(0, queue.getNumInProgress());
    assertEquals(3, queue.getQueueSize());
    
    sel = queue.getNextURL(cw, fetcher);
    assertNotNull(sel);
    assertEquals(seed.getDocid(), sel.getDocid());
    assertEquals(1, queue.getNumInProgress());
    assertEquals(3, queue.getQueueSize());
    
    queue.setFinishedURL(cw, sel);
    fetcher.unselect(sel);
    assertEquals(0, queue.getNumInProgress());
    assertEquals(2, queue.getQueueSize());
    
    sel = queue.getNextURL(cw, fetcher);
    assertNotNull(sel);
    assertEquals(os4.getDocid(), sel.getDocid());
    assertEquals(1, queue.getNumInProgress());
    assertEquals(2, queue.getQueueSize());
    
    queue.setFinishedURL(cw, sel);
    fetcher.unselect(sel);
    assertEquals(0, queue.getNumInProgress());
    assertEquals(1, queue.getQueueSize());
    
    sel = queue.getNextURL(cw, fetcher);
    assertNotNull(sel);
    assertEquals(os3.getDocid(), sel.getDocid());
    assertEquals(1, queue.getNumInProgress());
    assertEquals(1, queue.getQueueSize());
    
    queue.setFinishedURL(cw, sel);
    fetcher.unselect(sel);
    assertEquals(0, queue.getNumInProgress());
    assertEquals(0, queue.getQueueSize());
  }
  
  /**
   * This test inserts URLs with decreasing priority in order to perform
   * tail inserts and then requests the head of the queue.
   * 
   * This tests occurence of a bug where after getNextURL, hq.head would become
   * hq.head + 1, being equal to hq.tail. In this situation, hq.head and hq.tail
   * would refer to the same URL in the database but would not be the same object,
   * resulting in inconsistences when updating the URL. This bug in getNextURL
   * has been fixed.
   * 
   * @throws URISyntaxException Never
   * @throws QueueException When the queue is currupted due to bugs
   */
  @Test
  public void testBugHeadTailEquality() throws URISyntaxException, QueueException {
    WebURL seed = new WebURL("http://www.test.com/");
    seed.setDocid(1);
    seed.setSeedDocid(1);
    seed.setPriority((byte)-2);
    seed.setDepth((short)0);
    assertTrue(queue.enqueue(seed));
    assertEquals(1, queue.getQueueSize());
    assertEquals(0, queue.getNumInProgress());
    
    WebURL os1 = new WebURL("http://www.test.com/1");
    os1.setDocid(2);
    os1.setSeedDocid(1);
    os1.setPriority((byte)-1);
    os1.setDepth((short)1);
    assertTrue(queue.enqueue(os1));
    assertEquals(2, queue.getQueueSize());
    assertEquals(0, queue.getNumInProgress());
    
    Thread nt = new Thread();
    WebCrawler cw = new WebCrawler();
    cw.setThread(nt);
    WebURL sel = queue.getNextURL(cw, fetcher);
    assertNotNull(sel);
    assertEquals(seed.getDocid(), sel.getDocid());
    assertEquals(1, queue.getNumInProgress());
    assertEquals(2, queue.getQueueSize());
    
    queue.setFinishedURL(cw, sel);
    assertEquals(0, queue.getNumInProgress());
    assertEquals(1, queue.getQueueSize());
    assertTrue(validate());
    
    WebURL os2 = new WebURL("http://www.test.com/2");
    os2.setDocid(3);;
    os2.setSeedDocid(1);
    os2.setDepth((short)2);
    os2.setPriority((byte)0);
    assertTrue(queue.enqueue(os2));
    
    assertTrue(validate());
  }
  
  
  @Test
  public void testMultiEnqueueFunctionality() throws URISyntaxException, QueueException {
    log.put("www.test.com", new ArrayList<String>());
    
    WebURL seed = new WebURL("http://www.test.com/");
    seed.setDocid(1);
    seed.setSeedDocid(1);
    seed.setPriority((byte)-2);
    seed.setDepth((short)0);
    log.get("www.test.com").add("Added URL ##" + seed.getDocid() + "## - from seed [[" + seed.getSeedDocid() + "]]: " + seed.getURL() + " with priority " + seed.getPriority() + " and depth: " + seed.getDepth());
    
    assertTrue(queue.enqueue(seed));
    assertEquals(1, queue.getQueueSize());
    assertEquals(0, queue.getNumInProgress());
    
    {
      WebURL os = new WebURL("http://www.test.com/0");
      os.setDocid(2);
      os.setSeedDocid(1);
      os.setPriority((byte)0);
      os.setDepth((short)2);
      os.setParentDocid(1);
      assertTrue(queue.enqueue(os));
      log.get("www.test.com").add("Added URL ##" + os.getDocid() + "## - from seed [[" + os.getSeedDocid() + "]]: " + os.getURL() + " with priority " + os.getPriority() + " and depth: " + os.getDepth());
    }
    
    assertEquals(2, queue.getQueueSize());
    assertEquals(0, queue.getNumInProgress());
    
    ArrayList<WebURL> new_urls = new ArrayList<WebURL>();
    {
      WebURL os = new WebURL("http://www.test.com/1");
      os.setDocid(3);
      os.setSeedDocid(1);
      os.setPriority((byte)-1);
      os.setDepth((short)2);
      os.setParentDocid(1);
      new_urls.add(os);
      log.get("www.test.com").add("Added URL ##" + os.getDocid() + "## - from seed [[" + os.getSeedDocid() + "]]: " + os.getURL() + " with priority " + os.getPriority() + " and depth: " + os.getDepth());
    }
    
    {
      WebURL os = new WebURL("http://www.test.com/2");
      os.setDocid(4);
      os.setSeedDocid(1);
      os.setDepth((short)2);
      os.setPriority((byte)-1);
      os.setParentDocid(1);
      new_urls.add(os);
      log.get("www.test.com").add("Added URL ##" + os.getDocid() + "## - from seed [[" + os.getSeedDocid() + "]]: " + os.getURL() + " with priority " + os.getPriority() + " and depth: " + os.getDepth());
    }
    
    {
      WebURL os = new WebURL("http://www.test.com/3");
      os.setDocid(5);
      os.setSeedDocid(1);
      os.setDepth((short)2);
      os.setPriority((byte)-1);
      os.setParentDocid(1);
      new_urls.add(os);
      log.get("www.test.com").add("Added URL ##" + os.getDocid() + "## - from seed [[" + os.getSeedDocid() + "]]: " + os.getURL() + " with priority " + os.getPriority() + " and depth: " + os.getDepth());
    }
    
    assertTrue(queue.enqueue(new_urls).isEmpty());
    
    //if (queue.getQueueSize() != 9)
      queue.showHostQueue("www.test.com");
    
    assertEquals(5, queue.getQueueSize());
    assertTrue(validate());
    
    queue.removeOffspring(1);
  }
  /**
   * This test performs a random batch of common operations on the queue
   * and performs a validateQueue operation after each of them in order to make
   * sure the queue administration is handled properly. For a more thorough
   * test, increase the numbers in the addURLs invocations.
   * 
   * @throws URISyntaxException When an incorrect URL is generated. Shouldn't happen
   * @throws QueueException When the queue is corrupted due to a bug
   */
  @Test
  public void testRandomHostQueueUtilization() throws URISyntaxException, QueueException {
    if (true)
      return;
    
    Random gen = new Random(1234);
    addURLs(gen, 100, 0.25);
    System.out.println("Generated " + queue.getQueueSize() + " urls");
    assertTrue(validate());
    
    Thread nt = new Thread();
    WebCrawler cw = new WebCrawler();
    cw.setThread(nt);
    
    while (queue.getQueueSize() > 0) {
      WebURL url = queue.getNextURL(cw, fetcher);
      assertTrue(validate());
      if (url == null)
        continue;
      
      log.get(url.getURI().getHost()).add("Selected doc ##" + url.getDocid() + "## of seed [[" + url.getSeedDocid() + "]] - prio: " + url.getPriority() + " depth: " + url.getDepth() + " URL: " + url.getURL());
      
      int ab = gen.nextInt(100);
      if (ab > 70) {
        log.get(url.getURI().getHost()).add("Abandoned doc ##" + url.getDocid() + "## of seed [[" + url.getSeedDocid() + "]] - prio: " + url.getPriority() + " depth: " + url.getDepth() + " URL: " + url.getURL());
        queue.abandon(cw, url);
        assertTrue(validate());
        continue;
      }
      
      log.get(url.getURI().getHost()).add("Finished doc ##" + url.getDocid() + "## of seed [[" + url.getSeedDocid() + "]] - prio: " + url.getPriority() + " depth: " + url.getDepth() + " URL: " + url.getURL());
      queue.setFinishedURL(cw, url);
      removeURL(url.getDocid());
      fetcher.unselect(url);
      assertTrue(validate());
      
      int del = gen.nextInt(100);
      if (del > 60) {
        log.get(url.getURI().getHost()).add("Removing all offspring for seed: [[" + url.getSeedDocid() + "]]");
        queue.removeOffspring(url.getSeedDocid());
        assertTrue(validate());
        
        // Find and remove seed
        removeSeed(url.getSeedDocid());
      }
      
      int add = gen.nextInt(100);
      if (add > 90)
        addURLs(gen, gen.nextInt(20), 0.15);
    }
  }

  private void runThread(int id) throws QueueException, URISyntaxException {
    Thread ct = Thread.currentThread();
    Random gen = new Random(id);
        
    try {
      Thread.sleep(500);
    } catch (InterruptedException e)
    {}
    
    WebCrawler cw = new WebCrawler();
    cw.setMyId(id + 1);
    ct.setName("Crawler " + (id + 1));
    cw.setThread(ct);
    
    while (queue.getQueueSize() > 0 && !failed) {
      WebURL url;
      synchronized (queue) {
        url = queue.getNextURL(cw, fetcher);
        assertTrue(validate());
      }
      
      if (url == null)
        continue;
      
      log.get(url.getURI().getHost()).add("Selected doc ##" + url.getDocid() + "## of seed [[" + url.getSeedDocid() + "]] - prio: " + url.getPriority() + " depth: " + url.getDepth() + " URL: " + url.getURL());
      
      int ab = gen.nextInt(100);
      if (ab > 70) {
        log.get(url.getURI().getHost()).add("Abandoned doc ##" + url.getDocid() + "## of seed [[" + url.getSeedDocid() + "]] - prio: " + url.getPriority() + " depth: " + url.getDepth() + " URL: " + url.getURL());
        synchronized (queue) {
          queue.abandon(cw, url);
          assertTrue(validate());
        }
        continue;
      }
      
      try {
        Thread.sleep(500 + gen.nextInt(2500));
      } catch (InterruptedException e) {}
      
      log.get(url.getURI().getHost()).add("Finished doc ##" + url.getDocid() + "## of seed [[" + url.getSeedDocid() + "]] - prio: " + url.getPriority() + " depth: " + url.getDepth() + " URL: " + url.getURL());
      synchronized (queue) {
        queue.setFinishedURL(cw, url);
        removeURL(url.getDocid());
        fetcher.unselect(url);
        assertTrue(validate());
      }
      
      int del = gen.nextInt(100);
      if (del > 60) {
        log.get(url.getURI().getHost()).add("Removing all offspring for seed: [[" + url.getSeedDocid() + "]]");
        synchronized (queue) {
          queue.removeOffspring(url.getSeedDocid());
          assertTrue(validate());
          // Find and remove seed
          removeSeed(url.getSeedDocid());
        }
      }
      
      int add = gen.nextInt(100);
      if (add > 90) {
        synchronized (queue) {
          addURLs(gen, gen.nextInt(20), 0.15);
        }
      }
    }
    System.out.println("Crawler " + (id + 1) + " has finished, goodbye");
  }
  
  /**
   * This test performs a random batch of common operations on the queue
   * and performs a validateQueue operation after each of them in order to make
   * sure the queue administration is handled properly. For a more thorough
   * test, increase the numbers in the addURLs invocations.
   * 
   * @throws URISyntaxException When an incorrect URL is generated. Shouldn't happen
   * @throws QueueException When the queue is invalid
   */
  @Test
  public void testMPRandomHostQueueUtilization() throws URISyntaxException, QueueException {
    if (true)
      return;
    
    int nthreads = 8;
    Thread [] threads = new Thread[nthreads];
    final Reference<Integer> cnt = new Reference<Integer>(0);
    for (int i = 0; i < nthreads; ++i) {
      threads[i] = new Thread(new Runnable() { 
        public void run() {
          int myid = ++cnt.val;
          try {
            runThread(myid);
          } catch (Exception e) {
            last_exception = e;
            failed = true;
          }
        }
      });
      threads[i].start();
    }
    
    Random gen = new Random(1234);
    
    synchronized (queue) {
      System.out.println("Generated " + queue.getQueueSize() + " urls");
      addURLs(gen, 100, 0.25);
      assertTrue(validate());
    }
    
    while (true) {
      if (last_exception != null)
        last_exception.printStackTrace(System.err);
      
      assertFalse(failed);
      assertNull(last_exception);
      int alive = 0;
      for (Thread t : threads) {
        if (t.isAlive())
          ++alive;
      }
      if (alive > 0) {
        System.out.println(alive + " threads are still alive, waiting");
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {}
      } else {
        break;
      }
    }
    assertEquals(0, queue.getQueueSize());
  }
  
  /**
   * Validate the host queue, and, if an error occurs,
   * list the events leading up to that event.
   * 
   * @return True when the host queue is valid, false otherwise
   */
  private boolean validate() {
    String host = queue.validateQueue();
    
    if (host == null)
      return true;
    System.out.println("Problematic host: " + host);
    
    if (host == "*")
      return false;
    
    ArrayList<String> clog = log.get(host);
    System.err.println("---- LOG FOR HOST " + host);
    for (String line : clog)
      System.err.println(line);
    System.err.println("---- END LOG FOR HOST " + host);
    
    return false;
  }

  /**
   * Add a number of random URLs to the queue.
   * 
   * @param gen The pre-seeded random number generator
   * @param num_urls The number of URLs to generate
   * @param seed_prob The probability of generating a seed URL rather than a child URL
   * @throws URISyntaxException When an invalid URL is generated. Should not happen.
   */
  private void addURLs(Random gen, int num_urls, double seed_prob) throws URISyntaxException {
    boolean batch = gen.nextBoolean();
    ArrayList<WebURL> new_urls = new ArrayList<WebURL>();
    
    for (int i = 0; i < num_urls; ++i) {
      String rnd_string = UUID.randomUUID().toString().substring(0, 6);

      int max_int = seed_prob > 0 ? (int)Math.round(urls.size() / (1 - seed_prob)) : urls.size();
      int parentn = max_int > 0 ? gen.nextInt(max_int) : -1;
      if (parentn == -1 || parentn >= urls.size()) {
        // Generate new seed
        String url = "http://" + rnd_string + ".com/";
        
        WebURL s = new WebURL(url);
        s.setDocid(next_doc_id++);
        s.setSeedDocid(s.getDocid());
        s.setDepth((short)0);
        byte prio = (byte)(gen.nextInt(32) - 16);
        s.setPriority(prio);
        //s.setPriority((byte)0);
        
        if (batch) {
          new_urls.add(s);
        } else {
          assertTrue(queue.enqueue(s));
          urls.add(s);
          seeds.add(s);
        }
        
        String host = rnd_string + ".com";
        log.put(host, new ArrayList<String>());
        log.get(host).add("Added seed [[" + s.getSeedDocid() + "]]: " + url + " with priority " + s.getPriority() + (batch ? "(batch)" : ""));
      } else {
        // Generate new offspring
        WebURL parent = urls.get(parentn);
        String url = "http://" + parent.getURI().getHost() + "/" + rnd_string;
        WebURL t = new WebURL(url);
        t.setDocid(next_doc_id++);
        t.setParentDocid(parent.getDocid());
        t.setSeedDocid(parent.getSeedDocid());
        t.setDepth((short)(parent.getDepth() + 1));
        int prio = Math.min(Byte.MAX_VALUE, parent.getPriority() + (gen.nextInt(6) - 3));
        prio = Math.max(Byte.MIN_VALUE, prio);
        t.setPriority((byte)prio);
        //t.setPriority((byte)0);
        
        if (batch) {
          new_urls.add(t);
        } else {
          assertTrue(queue.enqueue(t));
          urls.add(t);
        }
        
        String host = parent.getURI().getHost();
        log.get(host).add("Added URL ##" + t.getDocid() + "## - from seed [[" + t.getSeedDocid() + "]]: " + url + " with priority " + t.getPriority() + " and depth: " + t.getDepth() + (batch ? "(batch)" : ""));
      }
    }
    
    if (batch) {
      assertTrue(queue.enqueue(new_urls).isEmpty());
      urls.addAll(new_urls);
      for (WebURL u : new_urls) {
        if (u.getSeedDocid() == u.getDocid())
          seeds.add(u);
      }
    }
    assertTrue(validate());
  }
  
  /**
   * Helper to remove local administration of a seed URL
   * 
   * @param docid The docid of the seed to remove
   */
  private void removeSeed(long docid) {
    Iterator<WebURL> it = seeds.iterator();
    while (it.hasNext()) {
      WebURL surl = it.next();
      if (surl.getDocid() == docid) {
        it.remove();
        break;
      }
    }
    
    it = urls.iterator();
    while (it.hasNext()) {
      WebURL surl = it.next();
      if (surl.getSeedDocid() == docid) {
        it.remove();
      }
    }
  }

  /**
   * Helper to remove local administration of a URL
   * 
   * @param docid The docid to remove
   */
  private void removeURL(long docid) {
    Iterator<WebURL> it = urls.iterator();
    while (it.hasNext()) {
      WebURL surl = it.next();
      if (surl.getDocid() == docid) {
        it.remove();
        break;
      }
    }
  }
}