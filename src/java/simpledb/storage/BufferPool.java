package simpledb.storage;

import simpledb.common.Database;
import simpledb.common.Permissions;
import simpledb.common.DbException;
import simpledb.common.DeadlockException;
import simpledb.transaction.TransactionAbortedException;
import simpledb.transaction.TransactionId;

import java.io.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BufferPool manages the reading and writing of pages into memory from
 * disk. Access methods call into it to retrieve pages, and it fetches
 * pages from the appropriate location.
 * <p>
 * The BufferPool is also responsible for locking;  when a transaction fetches
 * a page, BufferPool checks that the transaction has the appropriate
 * locks to read/write the page.
 * 
 * @Threadsafe, all fields are final
 */
class LockManager {
    enum LockType {
        SLock, XLock
    }

    class ObjLock {
        // boolean blocked;
        LockType type;
        PageId obj;
        ArrayList<TransactionId> holders;

        /*
        public boolean isBlocked() {
            return blocked;
        }
        public void setBlocked(boolean blocked) {
            this.blocked = blocked;
        }
        */

        public ObjLock(LockType t, PageId obj, ArrayList<TransactionId> holders) {
            // this.blocked = false;
            this.type = t;
            this.obj = obj;
            this.holders = holders;
        }

        public void setType(LockType type) {
            this.type = type;
        }

        public LockType getType() {
            return type;
        }

        public PageId getObj() {
            return obj;
        }

        public ArrayList<TransactionId> getHolders() {
            return holders;
        }

        public boolean tryUpgradeLock(TransactionId tid) {
            if (type == LockType.SLock && holders.size() == 1 && holders.get(0).equals(tid)) {
                type = LockType.XLock;
                return true;
            }
            return false;
        }

        public TransactionId addHolder(TransactionId tid) {
            if (type == LockType.SLock) {
                if (!holders.contains(tid)) {
                    holders.add(tid);
                }
                return tid;
            }
            return null;
        }
    }

    private ConcurrentHashMap<PageId, ObjLock> lockTable;
    private ConcurrentHashMap<TransactionId, ArrayList<PageId>> transactionTable;

    public LockManager(int lockTabCap, int transTabCap) {
        this.lockTable = new ConcurrentHashMap<>(lockTabCap);
        this.transactionTable = new ConcurrentHashMap<>(transTabCap);
    }

    public synchronized boolean holdsLock(TransactionId tid, PageId pid) {
        ArrayList<PageId> lockList = getLockList(tid);
        return lockList != null && lockList.contains(pid);
    }

    private synchronized void block(PageId what, long start, long timeout)
            throws TransactionAbortedException {
        // activate blocking
        // lockTable.get(what).setBlocked(true);

        if (System.currentTimeMillis() - start > timeout) {
            // System.out.println(Thread.currentThread().getId() + ": aborted");
            throw new TransactionAbortedException();
        }

        try {
            wait(timeout);
            if (System.currentTimeMillis() - start > timeout) {
                // System.out.println(Thread.currentThread().getId() + ": aborted");
                throw new TransactionAbortedException();
            }
        } catch (InterruptedException e) {
            /* do nothing */
            e.printStackTrace();
        }
    }

    private synchronized void updateTransactionTable(TransactionId tid, PageId pid) {
        if (transactionTable.containsKey(tid)) {
            if (!transactionTable.get(tid).contains(pid)) {
                transactionTable.get(tid).add(pid);
            }
        } else {
            // no entry tid
            ArrayList<PageId> lockList = new ArrayList<PageId>();
            lockList.add(pid);
            transactionTable.put(tid, lockList);
        }
    }

    public synchronized void acquireLock(TransactionId tid, PageId pid, LockType reqLock, int maxTimeout)
            throws TransactionAbortedException {
        // boolean isAcquired = false;
        long start = System.currentTimeMillis();
        Random rand = new Random();
        long randomTimeout = rand.nextInt((maxTimeout - 0) + 1) + 0;
        while (true) {
            if (lockTable.containsKey(pid)) {
                // page is locked by some transaction
                if (lockTable.get(pid).getType() == LockType.SLock) {
                    if (reqLock == LockType.SLock) {
                        updateTransactionTable(tid, pid);
                        assert lockTable.get(pid).addHolder(tid) != null;
                        // isAcquired = true;
                        return;
                    } else {
                        // request XLock
                        if (transactionTable.containsKey(tid) && transactionTable.get(tid).contains(pid)
                                && lockTable.get(pid).getHolders().size() == 1) {
                            // sanity check
                            assert lockTable.get(pid).getHolders().get(0) == tid;
                            // this is a combined case when lock on pid hold only by one trans (which is exactly tid)
                            lockTable.get(pid).tryUpgradeLock(tid);
                            // isAcquired = true;
                            return;
                        } else {
                            // all need to do is just blocking
                            block(pid, start, randomTimeout);
                        }
                    }
                } else {
                    // already get a Xlock on pid
                    if (lockTable.get(pid).getHolders().get(0) == tid) {
                        // Xlock means only one holder
                        // request xlock or slock on the pid with that tid
                        // sanity check
                        assert lockTable.get(pid).getHolders().size() == 1;
                        // isAcquired = true;
                        return;
                    } else {
                        // otherwise block
                        block(pid, start, randomTimeout);
                    }
                }
            } else {
                ArrayList<TransactionId> initialHolders = new ArrayList<>();
                initialHolders.add(tid);
                lockTable.put(pid, new ObjLock(reqLock, pid, initialHolders));
                updateTransactionTable(tid, pid);
                // isAcquired = true;
                return;
            }
        }
    }

    public synchronized void releaseLock(TransactionId tid, PageId pid) {

        // remove from trans table
        if (transactionTable.containsKey(tid)) {
            transactionTable.get(tid).remove(pid);
            if (transactionTable.get(tid).size() == 0) {
                transactionTable.remove(tid);
            }
        }

        // remove from locktable
        if (lockTable.containsKey(pid)) {
            lockTable.get(pid).getHolders().remove(tid);
            if (lockTable.get(pid).getHolders().size() == 0) {
                // no more threads are waiting here
                lockTable.remove(pid);
            } else {
                // ObjLock lock = lockTable.get(pid);
                // synchronized (lock) {
                notifyAll();
                //}
            }
        }
    }

    public synchronized void releaseLocksOnTransaction(TransactionId tid) {
        if (transactionTable.containsKey(tid)) {
        	//System.out.println("11");
            //PageId[] pidArr = new PageId[transactionTable.get(tid).size()];
            PageId[] toRelease = transactionTable.get(tid).toArray(new PageId[] {});
            for (PageId pid : toRelease) {
                releaseLock(tid, pid);
            }

        }
    }

    public synchronized ArrayList<PageId> getLockList(TransactionId tid) {
        return transactionTable.getOrDefault(tid, null);
    }
}
public class BufferPool {
    /** Bytes per page, including header. */
    private static final int DEFAULT_PAGE_SIZE = 4096;

    private static int pageSize = DEFAULT_PAGE_SIZE;
    
    /** Default number of pages passed to the constructor. This is used by
    other classes. BufferPool should use the numPages argument to the
    constructor instead. */
    public static final int DEFAULT_PAGES = 50;
    
    private int numPages = 0;
    private ConcurrentHashMap<PageId,Page> PagesMap = null;
    
    private LockManager lockMgr;
    private static int TRANSATION_FACTOR = 2;
    // timeout 1s for deadlock detection
    private static int DEFAUT_MAXTIMEOUT = 1500;
    
    ArrayList<PageId> queue;

    /**
     * Creates a BufferPool that caches up to numPages pages.
     *
     * @param numPages maximum number of pages in this buffer pool.
     */
    public BufferPool(int numPages) {
        // some code goes here
    	this.numPages = numPages;
    	PagesMap = new ConcurrentHashMap<PageId,Page>();
    	this.lockMgr = new LockManager(numPages, TRANSATION_FACTOR * numPages);
    	queue = new ArrayList<PageId>();
    }
    
    public static int getPageSize() {
      return pageSize;
    }
    
    // THIS FUNCTION SHOULD ONLY BE USED FOR TESTING!!
    public static void setPageSize(int pageSize) {
    	BufferPool.pageSize = pageSize;
    }
    
    // THIS FUNCTION SHOULD ONLY BE USED FOR TESTING!!
    public static void resetPageSize() {
    	BufferPool.pageSize = DEFAULT_PAGE_SIZE;
    }

    /**
     * Retrieve the specified page with the associated permissions.
     * Will acquire a lock and may block if that lock is held by another
     * transaction.
     * <p>
     * The retrieved page should be looked up in the buffer pool.  If it
     * is present, it should be returned.  If it is not present, it should
     * be added to the buffer pool and returned.  If there is insufficient
     * space in the buffer pool, a page should be evicted and the new page
     * should be added in its place.
     *
     * @param tid the ID of the transaction requesting the page
     * @param pid the ID of the requested page
     * @param perm the requested permissions on the page
     */
    int count = 0;
    public  Page getPage(TransactionId tid, PageId pid, Permissions perm)
        throws TransactionAbortedException, DbException {
        // some code goes here
    	//System.out.println("111");
    	LockManager.LockType lockType;
    	if (perm == Permissions.READ_ONLY) {
            lockType = LockManager.LockType.SLock;
        } else {
            lockType = LockManager.LockType.XLock;
        }
    	lockMgr.acquireLock(tid, pid, lockType, DEFAUT_MAXTIMEOUT);
    	
    	if(PagesMap.containsKey(pid))
    	{
    		
    		//System.out.println("--------------------we find Pages!"+pid);
    		queue.remove(pid);
    		queue.add(pid);
    		return PagesMap.get(pid);
    	}
    	else {
    		//System.out.println("calc hashcode!");
    		if(PagesMap.size() >= this.numPages)
    		{
    			evictPage();//TODO
    		}
			PagesMap.put(pid, Database.getCatalog()
					.getDatabaseFile(pid.getTableId())
					.readPage(pid));
			queue.add(pid);
			count++;
    			//if( PagesMap.contains(pid) )
    				//System.out.println("success!!");
    			//System.out.println("numPages:"+numPages+"   count:"+count);
    		
    	}
    	//System.out.println("page:"+PagesMap.get(pid));
    	//System.out.println("pid:"+pid);
    	//queue.remove(pid);
    	//System.out.println(pid+" "+((HeapPage)PagesMap.get(pid)).getNumEmptySlots());
        return PagesMap.get(pid);
    }

    /**
     * Releases the lock on a page.
     * Calling this is very risky, and may result in wrong behavior. Think hard
     * about who needs to call this and why, and why they can run the risk of
     * calling it.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param pid the ID of the page to unlock
     */
    public  void unsafeReleasePage(TransactionId tid, PageId pid) {
        // some code goes here
        // not necessary for lab1|lab2
    	lockMgr.releaseLock(tid, pid);
    }

    /**
     * Release all locks associated with a given transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @throws IOException 
     */
    public void transactionComplete(TransactionId tid) {
        // some code goes here
        // not necessary for lab1|lab2
    	transactionComplete(tid, true);
    }

    /** Return true if the specified transaction has a lock on the specified page */
    public boolean holdsLock(TransactionId tid, PageId p) {
        // some code goes here
        // not necessary for lab1|lab2
    	return lockMgr.holdsLock(tid, p);
        //return false;
    }

    /**
     * Commit or abort a given transaction; release all locks associated to
     * the transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param commit a flag indicating whether we should commit or abort
     * @throws IOException 
     */
    public void transactionComplete(TransactionId tid, boolean commit) {
        // some code goes here
        // not necessary for lab1|lab2
        ArrayList<PageId> lockList = lockMgr.getLockList(tid);
        if (lockList != null) {
            for (PageId pid : lockList) {
                Page pg = PagesMap.getOrDefault(pid, null);
                if (pg != null) {
                    if (commit) {
                        try {
							flushPage(pg.getId());
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
                        pg.setBeforeImage();
                        //TODO commit log ?????
                    } else if (pg.isDirty() != null){
                        // all dirty pages are flushed and not dirty page are still in cache
                        // discard
                        discardPage(pid);
                    }
                }
            }
        }

        // release locks finally
        lockMgr.releaseLocksOnTransaction(tid);
    }

    /**
     * Add a tuple to the specified table on behalf of transaction tid.  Will
     * acquire a write lock on the page the tuple is added to and any other 
     * pages that are updated (Lock acquisition is not needed for lab2). 
     * May block if the lock(s) cannot be acquired.
     * 
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit, and adds versions of any pages that have 
     * been dirtied to the cache (replacing any existing versions of those pages) so 
     * that future requests see up-to-date pages. 
     *
     * @param tid the transaction adding the tuple
     * @param tableId the table to add the tuple to
     * @param t the tuple to add
     */
    public void insertTuple(TransactionId tid, int tableId, Tuple t)
        throws DbException, IOException, TransactionAbortedException {
        // some code goes here
        // not necessary for lab1
    	DbFile cur = Database.getCatalog().getDatabaseFile(tableId);
    	ArrayList<Page> affectedPgs = (ArrayList<Page>)cur.insertTuple(tid, t);
        for (Page page : affectedPgs) {
            page.markDirty(true, tid);
            // bufferPool.replace(page.getId(), page);
            // bufferPool.remove(page.getId());
	        PagesMap.put(page.getId(), page);
        }
    }

    /**
     * Remove the specified tuple from the buffer pool.
     * Will acquire a write lock on the page the tuple is removed from and any
     * other pages that are updated. May block if the lock(s) cannot be acquired.
     *
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit, and adds versions of any pages that have 
     * been dirtied to the cache (replacing any existing versions of those pages) so 
     * that future requests see up-to-date pages. 
     *
     * @param tid the transaction deleting the tuple.
     * @param t the tuple to delete
     */
    public  void deleteTuple(TransactionId tid, Tuple t)
        throws DbException, IOException, TransactionAbortedException {
        // some code goes here
        // not necessary for lab1
    	DbFile cur = Database.getCatalog().getDatabaseFile(t.getRecordId().getPageId().getTableId());
    	ArrayList<Page> affectedPg = (ArrayList<Page>)cur.deleteTuple(tid, t);
        affectedPg.get(0).markDirty(true, tid);

        // bufferPool.remove(affectedPg.getId());
        PagesMap.put(affectedPg.get(0).getId(), affectedPg.get(0));
    }

    /**
     * Flush all dirty pages to disk.
     * NB: Be careful using this routine -- it writes dirty data to disk so will
     *     break simpledb if running in NO STEAL mode.
     */
    public synchronized void flushAllPages() throws IOException {
        // some code goes here
        // not necessary for lab1
    	//System.out.println("i'm flushAllPages");
    	for(PageId t : PagesMap.keySet() )
    	{
    		flushPage(t);
    	}
    }

    /** Remove the specific page id from the buffer pool.
        Needed by the recovery manager to ensure that the
        buffer pool doesn't keep a rolled back page in its
        cache.
        
        Also used by B+ tree files to ensure that deleted pages
        are removed from the cache so they can be reused safely
    */
    public synchronized void discardPage(PageId pid) {
        // some code goes here
        // not necessary for lab1
    	PagesMap.remove(pid);
    	queue.remove(pid);
    }

    /**
     * Flushes a certain page to disk
     * @param pid an ID indicating the page to flush
     */
    private synchronized  void flushPage(PageId pid) throws IOException {
        // some code goes here
        // not necessary for lab1
    	if( PagesMap.containsKey(pid) )
    	{
    		Page p = PagesMap.get(pid);
    		//System.out.println("???");
            TransactionId dirtier = p.isDirty();
            if (dirtier != null) {
                /*
                    append an update record to the log, with
                    a before-image and after-image.
                */
                Database.getLogFile().logWrite(dirtier, p.getBeforeImage(), p);
                Database.getLogFile().force();

                // then write back
                DbFile tb = Database.getCatalog().getDatabaseFile(p.getId().getTableId());
                p.markDirty(false, null);
                tb.writePage(p);
            }
    	}
    }

    /** Write all pages of the specified transaction to disk.
     */
    public synchronized  void flushPages(TransactionId tid) throws IOException {
        // some code goes here
        // not necessary for lab1|lab2
    	Enumeration<PageId> it = PagesMap.keys();
        while (it.hasMoreElements()) {
            flushPage(it.nextElement());
        }
    }

    /**
     * Discards a page from the buffer pool.
     * Flushes the page to disk to ensure dirty pages are updated on disk.
     */
    private synchronized  void evictPage() throws DbException {
        // some code goes here
        // not necessary for lab1
        //PageId pid = queue.get(0);
        try {
        	boolean flag = true;
        	for(int i =0;i < queue.size();i++)
        	{
        	//for(PageId pid:queue)
        	//{
        		PageId pid = queue.get(i);
        		if( PagesMap.get(pid).isDirty() != null )
        			continue;
	            flushPage(pid);
	            discardPage(pid);
	            flag = false;
	            break;
        	}
        	if( !flag )
        		return;
        } catch (IOException e) {
            throw new DbException("evictPage: unable to error when flush a page");
        }
    }

}
