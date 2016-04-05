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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;

import edu.uci.ics.crawler4j.url.WebURL;
import edu.uci.ics.crawler4j.util.Util;

/**
 * @author Yasser Ganjisaffar
 */
public class WorkQueues {
  private final Database urlsDB;
  private Database seedCountDB = null;
  private final Map<Long, Integer> seedCount = new HashMap<Long, Integer>();
  private final Environment env;

  private final boolean resumable;

  private final WebURLTupleBinding webURLBinding;

  protected final Object mutex = new Object();

  public WorkQueues(Environment env, String dbName, boolean resumable) {
    this.env = env;
    this.resumable = resumable;
    DatabaseConfig dbConfig = new DatabaseConfig();
    dbConfig.setAllowCreate(true);
    dbConfig.setTransactional(resumable);
    dbConfig.setDeferredWrite(!resumable);
    urlsDB = env.openDatabase(null, dbName, dbConfig);
    webURLBinding = new WebURLTupleBinding();
    
    // Load seed count from database
    if (resumable) {
      dbConfig.setSortedDuplicates(false);
      seedCountDB = env.openDatabase(null, dbName + "_seedcount", dbConfig);
      DatabaseEntry key = new DatabaseEntry();
      DatabaseEntry value = new DatabaseEntry();
      Transaction txn = beginTransaction();
      try (Cursor cursor = seedCountDB.openCursor(txn, null)) {
        OperationStatus result = cursor.getFirst(key, value, null);

        while (result == OperationStatus.SUCCESS) {
          if (value.getData().length > 0) {
            Long docid = Util.byteArray2Long(key.getData());
            Integer counterValue = Util.byteArray2Int(value.getData());
            seedCount.put(docid, counterValue);
          }
          result = cursor.getNext(key, value, null);
        }
      } finally {
        commit(txn);
      }
    }
  }

  protected Transaction beginTransaction() {
    return resumable ? env.beginTransaction(null, null) : null;
  }

  protected static void commit(Transaction tnx) {
    if (tnx != null) {
      tnx.commit();
    }
  }

  protected static void abort(Transaction txn) {
    if (txn != null) {
      txn.abort();
    }
  }

  protected Cursor openCursor(Transaction txn) {
    return urlsDB.openCursor(txn, null);
  }

  /**
   * Select *AND* remove the first set of items from the work queue
   * 
   * @param max The maximum number of items to return
   * @return The list of items, limited by max
   * @throws DatabaseException When the sleepycat database throws an error
   */
  public List<WebURL> shift(int max) throws DatabaseException {
    synchronized (mutex) {
      List<WebURL> results = new ArrayList<>(max);
      DatabaseEntry key = new DatabaseEntry();
      DatabaseEntry value = new DatabaseEntry();
      Transaction txn = beginTransaction();
      try (Cursor cursor = openCursor(txn)) {
        OperationStatus result = cursor.getFirst(key, value, null);
        int matches = 0;
        while ((matches < max) && result == OperationStatus.SUCCESS) {
          byte [] data = value.getData();
          cursor.delete();
          if (data.length > 0) {
            WebURL url = webURLBinding.entryToObject(value);
            seedDecrease(url.getSeedDocid());
            results.add(url);
            matches++;
          }
          result = cursor.getNext(key, value, null);
        }
      } catch (DatabaseException e) {
        abort(txn);
        txn = null;
        throw e;
      } finally {
        commit(txn);
      }
        
      return results;
    }
  }
  
  public int getSeedCount(Long docid) {
      synchronized (mutex) {
          return seedCount.containsKey(docid) ? seedCount.get(docid) : 0;
      }
  }
  
  private void setSeedCount(Long docid, Integer value) {
      DatabaseEntry key = new DatabaseEntry(Util.long2ByteArray(docid));
      if (value <= 0)
      {
          synchronized (mutex) {
              seedCount.remove(docid);
              if (seedCountDB != null) {
                  Transaction txn = env.beginTransaction(null, null);
                  seedCountDB.delete(txn, key);
                  txn.commit();
              }
          }
          return;
      }
      
      synchronized (mutex) {
          seedCount.put(docid, value);
          if (seedCountDB != null) {
              DatabaseEntry val = new DatabaseEntry(Util.int2ByteArray(value));
              Transaction txn = env.beginTransaction(null, null);
              seedCountDB.put(txn, key, val);
              txn.commit();
          }
      }
  }
  
  public void seedIncrease(Long docid) {
      seedIncrease(docid, 1);
  }
  
  public void seedIncrease(Long docid, Integer amount) {
      synchronized (mutex) {
          setSeedCount(docid, getSeedCount(docid) + amount);
      }
  }
  
  public void seedDecrease(Long docid) {
      seedIncrease(docid, -1);
  }
  
  public void seedDecrease(Long docid, Integer amount) {
      seedIncrease(docid, -amount);
  }

  /*
   * The key that is used for storing URLs determines the order
   * they are crawled. Lower key values results in earlier crawling.
   * Here our keys are 10 bytes. The first byte comes from the URL priority.
   * The second byte comes from depth of crawl at which this URL is first found.
   * The remaining 8 bytes come from the docid of the URL. As a result,
   * URLs with lower priority numbers will be crawled earlier. If priority
   * numbers are the same, those found at lower depths will be crawled earlier.
   * If depth is also equal, those found earlier (therefore, smaller docid) will
   * be crawled earlier.
   */
  protected static DatabaseEntry getDatabaseEntryKey(WebURL url) {
    byte[] keyData = new byte[10];
    
    // Because the ordering is done strictly binary, negative values will come last, because
    // their binary representation starts with the MSB at 1. In order to fix this, we'll have
    // to add the minimum value to become 0. This means that the maximum number will become
    // out of range in Byte-value, but the integer value is nicely converted down to the actual
    // binary representation that is useful here.
    byte binary_priority = (byte)(url.getPriority() - Byte.MIN_VALUE);
    keyData[0] = binary_priority;
    keyData[1] = (url.getDepth() > Byte.MAX_VALUE ? Byte.MAX_VALUE : (byte) url.getDepth());
    Util.putLongInByteArray(url.getDocid(), keyData, 2);
    return new DatabaseEntry(keyData);
  }

  public boolean put(WebURL url) {
    synchronized (mutex) {
      boolean added = false;
      DatabaseEntry value = new DatabaseEntry();
      webURLBinding.objectToEntry(url, value);
      Transaction txn = beginTransaction();
      // Check if the key already exists
      DatabaseEntry key = getDatabaseEntryKey(url);
      DatabaseEntry retrieve_value = new DatabaseEntry();
      if (urlsDB.get(txn, key, retrieve_value, null) == OperationStatus.NOTFOUND) {
        urlsDB.put(txn, key, value);
        seedIncrease(url.getSeedDocid());
        added = true;
      }
      commit(txn);
      return added;
    }
  }

  public long getLength() {
    return urlsDB.count();
  }

  public void close() {
    urlsDB.close();
  }
  
  public List<WebURL> getDump()
  {
      List<WebURL> list = new ArrayList<WebURL>();
      synchronized (mutex) {
          DatabaseEntry key = new DatabaseEntry();
          DatabaseEntry value = new DatabaseEntry();
          Transaction txn = beginTransaction();
          try (Cursor cursor = openCursor(txn)) {
            OperationStatus result = cursor.getFirst(key, value, null);
            while (result == OperationStatus.SUCCESS) {
              byte [] data = value.getData();
              if (data.length > 0) {
                WebURL url = webURLBinding.entryToObject(value);
                list.add(url);
             }
             result = cursor.getNext(key, value, null);
           }
         }
         commit(txn);
      }
      
      return list;
  }

  /**
   * Remove all offspring of the given seed docid
   * 
   * @param seed_doc_id
   * @return The number of elements removed
   */
  public int removeOffspring(long seed_doc_id) {
    synchronized (mutex) {
      int removed = 0;
      DatabaseEntry key = new DatabaseEntry();
      DatabaseEntry value = new DatabaseEntry();
      Transaction txn = beginTransaction();
      try (Cursor cursor = openCursor(txn))
      {
        OperationStatus result = cursor.getFirst(key, value, null);
        while (result == OperationStatus.SUCCESS) {
          byte [] data = value.getData();
          if (data.length > 0)
          {
            WebURL url = webURLBinding.entryToObject(value);
            if (url.getSeedDocid() == seed_doc_id) {
              cursor.delete();
              ++removed;
            }
          }
          result = cursor.getNext(key,  value, null);
        }
      }
      
      commit(txn);
      
      int cur = getSeedCount(seed_doc_id);
      if (cur != removed)
        throw new RuntimeException("Unmatching seed docids - there should be " + cur + " but only " + removed + " were removed");
      
      setSeedCount(seed_doc_id, 0);
      return removed;
    }
  }
}
