package com.dbvisit.replicate.plog.file;

/**
 * Copyright 2016 Dbvisit Software Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dbvisit.replicate.plog.config.PlogConfig;
import com.dbvisit.replicate.plog.config.PlogConfigType;
import com.dbvisit.replicate.plog.reader.DomainReader.DomainReaderBuilder;

/** 
 * PlogFileManager is responsible for finding PLOG files on disk and
 * producing a set of <em>PlogFile</em>s for converting a sub-stream 
 * of LCRs to <em>DomainRecord</em>s
 */
public class PlogFileManager {
    private static final Logger logger = LoggerFactory.getLogger(
        PlogFileManager.class
    );
    
    /** Static PLOG file suffix */
    private static final String PLOG_SUFFIX = "plog";
    /** URI of MINEs output PLOGs */
    private URI plogLocation;
    /** Scan interval defined by wait time in ms */
    private int scanInterval;
    /** Health check interval */ 
    private int healthCheckInterval;
    /** Time in ms to wait between scans for PLOGs */
    private int scanWaitTime;
    /** The interval to allow for no PLOGs prior to intercepting interrupts */
    private int scanQuitInterval;
    /** Whether or not the manager is active, eg. managing PLOGs */
    private boolean active = false;
    /** Number of scans done waiting for PLOGs */
    private int scanCount;
    /** The next PLOG to read from */
    private PlogFile nextPlog;
    /** The previously closed PLOG */
    private PlogFile prevPlog;
    /** Allow it to be forcefully interrupted when idle time out occurs */
    private boolean forceInterrupt = false;
    /** Whether or not the manager has resumed processing MINEs output */
    private boolean resumed = false;
    /** Cache the file name details */
    private final Map<Integer, LinkedList<PlogFileDetails>> fileDetails;
    /** The PLOG sequences that have been processed */
    private final Map<Integer, Boolean> processedSeq;
    /** Cache of potential restarts in PLOG sequence */
    private final Map<String, Boolean> restartBoundaryPlogs;
    /** All PLOGs produced by this file manager will use the same domain reader
     *  to read a sub-stream of data, as configured by read criteria */
    private final DomainReaderBuilder builder;
    
    /**
     * Create and configure PLOG file manager.
     * 
     * @param config PLOG session configuration
     * @param builder The DomainReaderBuilder to use to build DomainReaders
     *               for all PLOGs, used for converting sub-stream of LCRs 
     *               to domain records
     * 
     * @throws Exception Failed to configure PLOG file manager
     */
    public PlogFileManager (
        final PlogConfig config,
        final DomainReaderBuilder builder
    ) 
    throws Exception{
        init(config);
        if (!isValid()) {
            throw new Exception ("PLOG file manager has failed initialisation");
        }
        this.builder = builder;
        fileDetails  = new HashMap<Integer, LinkedList<PlogFileDetails>>();
        processedSeq = new HashMap<Integer, Boolean>();
        restartBoundaryPlogs = new HashMap<String, Boolean>();
    }
    
    /**
     * Create and configure PLOG file manager to start scanning at certain
     * PLOG sequence.
     * 
     * @param config  PLOG session configuration
     * @param builder The DomainReaderBuilder to use to build DomainReaders
     *                for all PLOGs, used for converting sub-stream of LCRs 
     *                to domain records
     * @param plogUID Unique ID of PLOG to start processing at
     * 
     * @throws Exception Failed to configure PLOG file manager
     */
    public PlogFileManager (
        final PlogConfig config, 
        final DomainReaderBuilder builder,
        final long plogUID
    ) throws Exception{
        init(config);
        if (!isValid()) {
            throw new Exception ("Fatal: PLOG file manager");
        }
        this.builder = builder;
        fileDetails  = new HashMap<Integer, LinkedList<PlogFileDetails>>();
        processedSeq = new HashMap<Integer, Boolean>();
        restartBoundaryPlogs = new HashMap<String, Boolean>();
        
        startAt (plogUID);
    }
    
    /**
     * Set the URI of replicate MINE output
     * 
     * @param location URI of on disk location of Processed LOGS
     */
    private void setPlogLocation (URI location) {
        this.plogLocation = location;
    }

    /**
     * Return the file URI to the output location of MINE on disk
     * 
     * @return file URI of sequence if PLOGs
     */
    public URI getPlogLocation() {
        return this.plogLocation;
    }

    /**
     * Set the number of wait intervals between scans, each interval is defined
     * by scan wait time
     * 
     * @see PlogFileManager#getScanWaitTime()
     * 
     * @param scanInterval number of wait interval
     */
    private void setScanInterval (int scanInterval) {
        this.scanInterval = scanInterval;
    }

    /**
     * Get the number of time intervals to wait before scanning for new
     * PLOGs in replicate sequence
     *  
     * @return number of wait intervals between scans
     */
    public int getScanInterval() {
        return this.scanInterval;
    }
    
    /**
     * Set the number of wait intervals when a health check should be done,
     * this merely logs a message to log file and do not have direct
     * control over replicate MINE
     * 
     * @param healthCheckInterval number of wait intervals that defines when a
     *                            health check message should be logged
     */
    private void setHealthCheckInterval (int healthCheckInterval) {
        this.healthCheckInterval = healthCheckInterval;
    }

    /**
     * Return the number of time intervals that defines when a health check
     * is done
     * 
     * @return number of wait intervals for logging a simple health check
     *         message
     */
    public int getHealthCheckInterval () {
        return this.healthCheckInterval;
    }

    /**
     * Set the time in milliseconds that defines a wait interval, used by
     * scans and health checks, this defines the length of sleep cycle for
     * monitoring PLOGs arrivings
     * 
     * @param scanWaitTime wait time (sleep) in milliseconds
     */
    private void setScanWaitTime (int scanWaitTime) {
        this.scanWaitTime = scanWaitTime;
    }

    /**
     * Return the wait (sleep) time in milliseconds that define a time
     * interval for scans and health checks
     * 
     * @return time in milliseconds
     */
    public int getScanWaitTime () {
        return this.scanWaitTime;
    }
    
    /**
     * Set the total number of health check intervals to consider as a
     * time out value, this is when allowing it to be forcefully interrupted,
     * will result in the PLOG file manager to quit scanning and shutdown
     * 
     * @param scanQuitCount the number of health checks counts to consider
     *                      as time out value to give up and consider MINE
     *                      offline
     */
    private void setScanQuitInterval (int scanQuitCount) {
        this.scanQuitInterval = scanQuitCount;
    }

    /**
     * Return the number of health check intervals to consider as a
     * time out for scanning for new PLOGs, if reached the PLOG file manager
     * will allow itself to be forcefully interrupted and shutdown of the
     * replicate stream processing
     * 
     * @return number of intervals to consider as value to quit scanning
     */
    public int getScanQuitInterval () {
        return this.scanQuitInterval;
    }

    /**
     * Set whether or not this PLOG file manager is already active, meaning
     * it has already started scanning a replication sequence or has
     * resumed scanning at a given replicate sequence
     * 
     * @param active true if already active and scanning, else false
     */
    private void setActive (boolean active) {
        this.active = active;
    }

    /**
     * Return whether or not this PLOG file manager is actively monitoring
     * a sequence of PLOGs in replication
     *  
     * @return true if monitoring a sequence of PLOGs, else false
     */
    public boolean isActive () {
        return this.active;
    }
    
    /**
     * The current count of number of scans performed without any PLOGs
     * arriving in the replicate sequence
     * 
     * @return the current count of unsuccessful scans done
     */
    public int getScanCount () {
        return this.scanCount;
    }

    /**
     * Increment the count of unsuccessful scans
     */
    private void incrementScanCount () {
        this.scanCount++;
    }
    
    /**
     * Once a successful scan was done, as in a PLOG file is now active,
     * we reset the unsuccessful scan count
     */
    private void resetScanCount () {
        this.scanCount = 0;
    }
    
    /**
     * Allow the client to set the PLOG file manager to be forcefully
     * interrupted once the ultimate off line time out occurs
     */
    public void setForceInterrupt() {
        forceInterrupt = true;
    }
    
    /**
     * Return whether or not this PLOG file manager have been set to 
     * allow forceful interrupts, as in thread interrupt
     * 
     * @return true if it can be interrupted when time out occurs, else false
     */
    private boolean forceInterrupt() {
        return forceInterrupt;
    }

    /**
     * Return the next PLOG file to read from, may be null if <em>scan()</em>
     * has not been called and/or no PLOGs are available
     * 
     * @return PLOG file null or valid PLOG to read from
     */
    public PlogFile getPlog () {
        return this.nextPlog;
    }
    
    /** 
     * Initialize the PLOG file manager from configuration object.
     * 
     * Would prefer to inject PlogConfig at runtime
     * 
     * @param config PLOG replicate session configuration
     * 
     * @throws Exception configuration error occurred
     */
    private void init (PlogConfig config) throws Exception {
        if (config == null) {
            throw new Exception ("No PLOG config provided");
        }
        setPlogLocation (
            new URI(config.getConfigValue(PlogConfigType.PLOG_LOCATION_URI))
        );
        setScanInterval (
            Integer.parseInt(
                config.getConfigValue(PlogConfigType.SCAN_INTERVAL_COUNT)
            )
        );
        setScanWaitTime (
            Integer.parseInt(
                config.getConfigValue(PlogConfigType.SCAN_WAIT_TIME_MS)
            )
        );
        setScanQuitInterval (
            Integer.parseInt(
                config.getConfigValue(
                    PlogConfigType.SCAN_QUIT_INTERVAL_COUNT
                )
            )
        );
        setHealthCheckInterval (
            Integer.parseInt(
                config.getConfigValue(
                    PlogConfigType.HEALTH_CHECK_INTERVAL_COUNT
                )
            )     
        );
    }

    /** 
     * Check that file manager is valid and ready to be used
     * 
     * @return boolean true if file manager is ready and valid, else false
     */
    private boolean isValid() {
        boolean valid = true;
        
        if (!(new File(plogLocation).canRead())) {
            logger.error (
                "The PLOG location: " + plogLocation + " is not accessible"
            );
            valid = false;
        }
        
        return valid;
    }
    
    /**
     * Search a directory and find the oldest PLOG, that is the one with
     * the lowest sequence number. <p>Used this method for a cold start.</p>
     * 
     * @return first sequence found. Integer.MAX_VALUE if no file present at
     *         all.
     * @throws Exception Failed to read PLOG location
     */
    public int findOldestPlogSequence () throws Exception {
        int rtval = Integer.MAX_VALUE;
        File dir = new File(plogLocation);
        if (!dir.canRead()) {
            throw new FileNotFoundException(plogLocation + " is not accessible");
        }
        
        File[] matchingFiles = dir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.toLowerCase().contains("." + PLOG_SUFFIX + ".");
            }
        });
        
        if (matchingFiles == null) {
            throw new Exception (
                "Invalid location for PLOGs: " + dir.toString()
            );
        }
        
        for (File mf : matchingFiles) {
            String p = null;
            try {
                p = mf.getName();
                p = p.substring(0, p.indexOf("." + PLOG_SUFFIX + "."));
                int s = Integer.parseInt(p);
                if (s < rtval) {
                    rtval = s;
                }
            }
            catch (NumberFormatException e) {
                logger.warn(p + " is not really a time number");
            }
            catch (IndexOutOfBoundsException e) {
                logger.warn(p + " malformed, did not find .plog extension.");
            }
        }
        return rtval;
    }

    private boolean activeMultiSequence () {
        boolean multi = false;
        
        if (prevPlog != null) {
            multi = fileDetails.containsKey(prevPlog.getId()) &&
                    !fileDetails.get(prevPlog.getId()).isEmpty();
        }
        
        return multi;
    }
    
    /**
     * Find the next PLOG file for a given sequence, supporting 
     * multi-sequence PLOGs.
     * 
     * <p>For transactional data PLOGs the file naming convention is:
     * &lt;PLOG ID&gt;.plog.&lt;timestamp&gt;. LOAD PLOGS are skipped,
     * the loading of these are triggered during parsing when a 
     * ESTYPE_LCR_PLOG_IFILE entry record is processed. 
     * 
     * @param plogSequence PLOG sequence to find next PLOG file for
     * 
     * @throws Exception No PLOG file found for sequence
     * 
     */
    public void findNextPlogFile (int plogSequence)
    throws Exception 
    {
        /* only find the next PLOG if the sequence hasn't been processed yet */
        if (!processedSeq.containsKey(plogSequence) ||
            processedSeq.get(plogSequence) != true)
        {
            /* check multi-sequence cache */
            if (fileDetails.containsKey(plogSequence) &&
                !fileDetails.get(plogSequence).isEmpty()) 
            {
                LinkedList<PlogFileDetails> cache = 
                    fileDetails.get(plogSequence);
                
                /* pop off next one */
                PlogFileDetails pfd = cache.pop();
                
                nextPlog = new PlogFile (
                    pfd.plogSequence, 
                    pfd.timestamp,
                    plogLocation.getPath().toString(),
                    pfd.fileName,
                    builder.build()
                );
                
                logger.trace (
                    "Found PLOG in multi-sequence cache: " + nextPlog.getFileName()
                );
                
                if (cache.isEmpty()) {
                    logger.trace ("Done with multi-sequence: " + plogSequence);
                    
                    /* if now empty we're done with sequence */
                    processedSeq.put (plogSequence, true);
                }
            }
            else {
                processedSeq.put (plogSequence, false);
                
                final String prefix = plogSequence + "." + PLOG_SUFFIX;
                File dir = new File(plogLocation);
                boolean atRestart = false;
                
                /* find matching major PLOGs for sequence, ignoring 
                 * LOAD PLOGs */
                File[] matchingFiles = dir.listFiles(new FilenameFilter() {
                    public boolean accept(File dir, String name) {
                        return name.toLowerCase().startsWith(prefix) &&
                               !name.matches(
                                   "^[0-9]+\\." + PLOG_SUFFIX + 
                                   "\\.[0-9]{10}+.*-[0-9]{6}-LOAD_.*"
                               );
                    }
                });
                
                if (matchingFiles == null) {
                    throw new Exception (
                        "Invalid location for PLOGs: " + dir.toString()
                    );
                }
                if (matchingFiles.length == 0) {
                    throw new FileNotFoundException(
                        "No file(s) found for sequence: " + plogSequence + 
                        " in PLOG location: " + plogLocation
                    );
                }
                
                /* we have matching file(s) */
                for (File mf : matchingFiles) {
                    if (logger.isDebugEnabled()) {
                        logger.trace (
                            "Found candidate PLOG: " + mf.getName() + " " +
                            "size: " + 
                            (new File (mf.getAbsolutePath())).length()
                        );
                    }
                    
                    if (!fileDetails.containsKey(plogSequence)) {
                        fileDetails.put (
                            plogSequence,
                            new LinkedList<PlogFileDetails>()
                        );
                    }
                    fileDetails.get (plogSequence).add (
                        new PlogFileDetails(mf)
                    );
                }
            
                LinkedList<PlogFileDetails> matchedFileDetails = 
                    fileDetails.get (plogSequence);
                
                if (matchedFileDetails.size() > 1) {
                    /* sort multi-part sequence of PLOGs */
                    Collections.sort(matchedFileDetails);
                    
                    /* multiple PLOGs found for this REDO sequence number, this
                     * can only occur at a restart boundary PLOG
                     */
                    atRestart = true;
                }
                
                PlogFileDetails pfd = matchedFileDetails.pop();
                
                nextPlog = new PlogFile (
                    pfd.plogSequence, 
                    pfd.timestamp,
                    plogLocation.getPath().toString(),
                    pfd.fileName,
                    builder.build()
                );
                
                if (atRestart) {
                    restartBoundaryPlogs.put (nextPlog.getFileName(), true);
                }
                
                if (matchedFileDetails.isEmpty()) {
                    /* we're done with this single part PLOG sequence */
                    processedSeq.put (plogSequence, true);
                }
            }
        }
    }
    
    /** Restarts the PLOG file manager at a specific PLOG file, it does so
     *  by cleaning out all existing scanning state
     * 
     * @param plogUID The unique ID for PLOG to start at
     * @throws Exception for any restart error
     */
    public void restartAt (long plogUID) throws Exception {
        if (isActive()) {
            reset();
        }
        startAt (plogUID);
    }
    
    /**
     * Prepare the PLOG file manager to start scanning at a certain 
     * PLOG in replication. NOTE: this does not open the PLOG file,
     * only prepares for the mandatory call to <em>scan()</em>.
     * 
     * @param plogUID unique ID for PLOG to start scanning at
     * 
     * @throws Exception Failed to start scanning at PLOG provided
     */
    public void startAt (long plogUID) throws Exception {
        int id        = PlogFile.getPlogIdFromUID(plogUID);
        int timestamp = PlogFile.getPlogTimestampFromUID(plogUID);
        
        if (id < 1) {
            throw new Exception (
                "Invalid PLOG sequence: " + id + ", uid: " + plogUID
            );
        }
        
        /* set the starting plog file, but don't use it, we need its
         * predecessor
         */
        findNextPlogFile(id);
        
        if (nextPlog.getUID() == plogUID) {
            /* found the one requested, so need to rewind to previous for
             * scan to work correctly for a warm start, watch out for
             * multi-sequence due to a replicate restart
             */
            int prevId = id - 1;
            
            logger.trace ("Reset scanning to PLOG sequence: " + prevId);
            
            try {
                findNextPlogFile(prevId);
                
                logger.trace (
                    "Reset next plog to previous plog: " + 
                    nextPlog.getFileName()
                );
            
                if (fileDetails.containsKey(prevId) &&
                    fileDetails.get(prevId).size() > 0) {
                    
                    logger.trace (
                        "Restarting at end of previous multi-sequence: " + 
                        prevId
                    );
               
                    int next = fileDetails.get(prevId).size();
                    
                    /* move it one to the last one that we would like to be 
                     * set to previous in replicate sequence
                     */
                    while (next > 0) {
                        findNextPlogFile(prevId);
                        next--;
                    }
                    logger.trace (
                        "Restarting previous multi-sequence at PLOG: " + 
                        nextPlog.getFileName()
                    );
                }
            }
            catch (FileNotFoundException fe) {
                /* fine, not physical previous PLOG */
                logger.trace (
                     "Previous PLOG at sequence: " + prevId + " is no " +
                     "longer available, mark it as empty file"
                );
                nextPlog.setId(prevId);
                nextPlog.setFileName("");
            }
            finally {
                /* reset, need to redo its scan */
                processedSeq.remove(id);
                fileDetails.remove (id);
            }
        }
        else if (fileDetails.containsKey(id) &&
                 fileDetails.get(id).size() > 0)
        {
            logger.trace ("Restarting with multi-sequence PLOG: " + id);
            
            int pop = 0;
            /* have multi-sequence for start sequence, use time stamp to
             * find previous one */
            for (PlogFileDetails fd : fileDetails.get(id)) {
                if (fd.timestamp < timestamp) {
                    /* do not have the one we want to start at */
                    pop++;
                }
            }
            
            /* pop the ones we do not want prior to previous one */
            while (pop > 1) {
                fileDetails.get(id).pop();
                pop--;
            }
            logger.trace (
                "Restarting multi-sequence at PLOG: " + nextPlog.getFileName()
            );
        }
        
        setActive (true);
        resetScanCount ();
        /* not a cold start, MINE is running, we're resuming */
        resumed = true;
    }
    
    /**
     * Prepare the PLOG file manager to start scanning after a certain 
     * PLOG in replication. NOTE: this does not open the PLOG file,
     * only prepares for the mandatory call to <em>scan()</em>.
     * 
     * @param plogUID prepare the scan to start at the next PLOG 
     *                after this unique ID
     * 
     * @throws Exception Failed to start scanning at PLOG provided
     */
    public void startAfter (long plogUID) throws Exception {
        int id = PlogFile.getPlogIdFromUID(plogUID);
        
        if (id < 1) {
            throw new Exception (
                "Invalid PLOG sequence: " + id + ", uid: " + plogUID
            );
        }
        
        /* first configure it to start at this PLOG */
        startAt(plogUID);
        /* the next plog is one to start at */
        findNextPlogFile(id);
    }
    
    /**
     * Blocking scan for PLOGs.
     * 
     * <p>Scan PLOG location for arrival of PLOGs, when new one arrives 
     * the previous one is closed and the new one opened getPlog()
     * This functions blocks until interrupted or until no PLOGs are
     * available due to MINE being offline which is determined from 
     * the configured retry count</p>
     * 
     * @throws InterruptedException When PLOG parsing have been interrupted
     * @throws Exception When PLOG parsing have been aborted due to fatal 
     *                   error.
     */
    public synchronized void scan () throws InterruptedException, Exception {
        logger.trace ("Scanning for PLOGs in: " + plogLocation.toString());
        if (!isActive()) {
            /* first scan */
            firstScan();
            return;
        }
        else {
            /* not a first scan, set previous PLOG */
            if (nextPlog != null && nextPlog != prevPlog) {
                prevPlog = nextPlog;
            }
        
            /* reset current PLOG */
            nextPlog = null;

            /* continue to scan for any multi-part sequences */
            if (!continueScan()) {
                /* block until PLOG arrives */
                nextScan();
            }
        }
        logger.trace ("Done scanning, have next PLOG: " + nextPlog.toString());
        
        return;
    }
    
    /** 
     * Perform a scan for PLOGs for a cold start, first scan will start at
     * the oldest PLOG, block until it arrives or idle timeout occurs
     * 
     * @throws InterruptedException if interrupted whilst scanning
     * @throws Exception if error occurred scanning for PLOGs, eg. redo
     *         reader is offline or PLOGs failed to open
     */
    private void firstScan () throws InterruptedException, Exception {
        int firstSequence = Integer.MAX_VALUE;

        int retries = 0;
        
        while (firstSequence == Integer.MAX_VALUE) {
            if (retries == 0) {
                Thread.sleep(scanWaitTime);
            }

            /* Find oldest plog */
            firstSequence = findOldestPlogSequence();
            
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }

            if (firstSequence < Integer.MAX_VALUE) {
                if (logger.isDebugEnabled()) {
                    logger.trace (
                        "First PLOG found on disk: "     + 
                        plogLocation.toString() + " is: " +
                        firstSequence);
                }
                break;
            }
            
            /* sleep and retry */
            retries++;
            Thread.sleep(scanWaitTime);

            /* Check for a dead redo reader at each health check interval */
            if ((retries % healthCheckInterval) == 0) {
                if (retries > scanQuitInterval) {
                    throw new Exception (
                        "Redo reader seems offline..."
                    );
                }
                logger.warn(
                    "No plog found, checking redo reader retry count: " +
                    retries
                );
            }
        }
        
        /* Get the starting plog file */
        findNextPlogFile(firstSequence);
        logger.debug (
            "Replicate stream starts with: " + nextPlog.getFileName()
        );

        /* open the first PLOG */
        openNextPlog ();

        /* file manager is now actively monitoring PLOGs */
        setActive (true);
        resetScanCount ();
    }
    
    /**
     * Continue scanning for further parts of PLOG sequence, if any
     * 
     * @returns true when done else false
     * @throws Exception if failed to scan
     */
    private boolean continueScan () throws Exception {
        boolean scan = true;
        
        if (prevPlog == null) {
            throw new Exception (
                "Unable to perform scan for multi-sequence PLOGs if no " +
                "previous PLOG exists"
            );
        }
        
        try {
            /* no need to continue scan if no multi sequence is active */
            if (activeMultiSequence()) {
                logger.debug (
                    "Finding next PLOG part of multi-sequence: " + 
                     prevPlog.getId()
                );
                findNextPlogFile(prevPlog.getId());
                openNextPlog ();
                scan = true;
            }
            else {
                scan = false;
            }
        }
        catch (FileNotFoundException fe) {
            /* no files for this sequence, continue with next */
            scan = false;
        }
        return scan;
    }
    
    /**
     * Scan for and prepares next PLOG in sequence for reading
     * 
     * @throws InterruptedException when interrupted
     * @throws Exception when error occurs
     */
    private void nextScan () 
    throws InterruptedException, Exception
    {
        while (nextPlog == null) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }

            try {
                assert (prevPlog != null);
                
                int nextSequence = prevPlog.getId() + 1;
                
                logger.trace (
                    "Scanning for next PLOG sequence: " + nextSequence
                );
                findNextPlogFile (nextSequence);
                openNextPlog();
            }
            catch (FileNotFoundException e) {
                if (logger.isTraceEnabled()) {
                    logger.trace (
                        "Waiting for next PLOG file to appear, reason: " + 
                        e.getMessage()
                    );
                }
                
                if (canQuit() && forceInterrupt()) {
                    logger.warn (
                        "Forcing shutdown of PLOG scanning, " +
                        "reason: idle timeout"
                    );
                    Thread.currentThread().interrupt();
                }
            }
            finally {
                incrementScanCount ();
            }
            if (nextPlog == null) {
                /* wait for a while */
                Thread.sleep(scanWaitTime);
            }
        }
    }
    
    /**
     * Open the next PLOG in the replicate sequence, this involves copying
     * the current PLOG's cache to the next PLOG in sequence, opening
     * the PLOG stream and reader and getting it ready for processing
     * the PLOG entries
     * 
     * @throws Exception when unable to open PLOG file and it's reader
     */
    private void openNextPlog () throws Exception {
        if (nextPlog == null) {
            throw new Exception ("Unable to open null PLOG file");
        }
        
        if (restartBoundaryPlogs.containsKey (nextPlog.getFileName())) {
            logger.debug (
                "Restart boundary PLOG found: " + nextPlog.getFileName()
            );
            /* enable boundary PLOG to forcefully close its data stream
             * if it wasn't finalized correctly during restart
             */
            nextPlog.enableForceCloseAtEnd();
        }

        if (prevPlog != null) {
            /* copy cache from previous and close */
            nextPlog.copyCacheFrom(prevPlog);
            prevPlog.close();
        }
        
        /* wait until MINE has at least written the PLOG control header */
        while (!Thread.currentThread().isInterrupted() &&
               (new File (nextPlog.getFullPath())).length() 
               < PlogFile.PLOG_DATA_BYTE_OFFSET) 
        {
            logger.info (
                "Waiting for control header to be written to PLOG file: " + 
                nextPlog.getFileName()
            );
            
            Thread.sleep(scanWaitTime * scanInterval);
        }
        
        logger.trace ("Opening next PLOG to read: " + nextPlog.getFileName());
        nextPlog.open();
    }
    
    /**
     * Return whether or not the condition for allowing the PLOG file
     * manager to quit (to be forcefully interrupted) has been met,
     * as in number of health checks equals or exceeds to configured
     * limit
     *  
     * @return true it PLOG file manager can be allowed to quit, else false
     */
    public boolean canQuit () {
        return (scanCount / healthCheckInterval) >= scanQuitInterval; 
    }
    
    /**
     * Return the total time out when MINE is considered to be offline.
     * 
     * When total scans / health check scans &gt; scan quit interval we
     * assume MINE is offline, 
     * eg. timeOut = scanQuitInterval 
     *             * healthCheckInterval 
     *             * scanInterval (in ms)
     *             
     * @return time out
     */
    public int getTimeOut () {
        return scanQuitInterval 
               * healthCheckInterval 
               * scanWaitTime;
    }
    
    /**
     * Close current and previous PLOGs, their data streams and readers.
     */
    public void close () {
        /* cleanly close PLOGs if open when interrupted or forced to close */
        if (nextPlog != null) {
            nextPlog.close();
        }
        
        if (prevPlog != null) {
            prevPlog.close();
        }
    }
    
    /**
     * Return simple textual description of a PLOG file manager
     * 
     * @return textual description of this object
     */
    public String toString () {
        StringBuilder sb = new StringBuilder();
        
        sb.append ("Plog file manager")
          .append (" URI: ")
          .append (plogLocation.toString())
          .append (" previous plog: ")
          .append (prevPlog != null ? prevPlog.getFileName() : "n/a")
          .append (" current plog: " )
          .append (nextPlog != null ? nextPlog.getFileName() : "n/a")
          .append (" active: ")
          .append (active)
          .append (" resumed: ")
          .append (resumed);
        
        return sb.toString();
    }
    
    /**
     * Reset all scan state for restarting or re-using file manager
     */
    private void reset () {
        resetScanCount();
        fileDetails.clear();
        processedSeq.clear();
        restartBoundaryPlogs.clear();
    }
    
    /** 
     * Simple class that implements Comparable to assist in sorting
     * PLOGs on disk
     */
    private class PlogFileDetails implements Comparable<PlogFileDetails> {
        /** Name of PLOG file on disk */
        public final String fileName;
        /** Sequence of PLOG file within replication */
        public final int plogSequence;
        /** The time stamp of PLOG file within replication */
        public final int timestamp;
        
        /**
         * Create sortable PLOG file details from file handle
         * 
         * @param file File handle for PLOG file on disk
         * 
         * @throws Exception when file provided to not meet PLOG requirements
         */
        public PlogFileDetails (File file) throws Exception {
            fileName = file.getName();
            
            /* parse fileName */
            if (!fileName.matches(
                    "^[0-9]+\\." + PLOG_SUFFIX + "\\.[0-9]{10}+.*$"
                )) 
            {
                throw new Exception (
                    "Invalid PLOG file to process: " + fileName + 
                    ", reason: do not following expected naming convention"
                );
            }
            
            int plogIndex = fileName.indexOf('.');
            int tendIndex = fileName.length();
            
            plogSequence = Integer.parseInt(fileName.substring(0, plogIndex));
            timestamp = Integer.parseInt(
                fileName.substring(
                    plogIndex + 1 + PLOG_SUFFIX.length() + 1,
                    tendIndex
                )
            );
        }
        
        /**
         * Compare this PLOG file to another by using file details, the
         * sequences and time stamps of the PLOGs within replication
         * 
         * @param o details of the other PLOG file to compare this one too
         */
        @Override
        public int compareTo (PlogFileDetails o) {
            int cmp = 0;
            if (plogSequence < o.plogSequence) {
                cmp = -1;
            }
            else if (plogSequence > o.plogSequence) {
                cmp = 1;
            }
            else {
                if (timestamp < o.timestamp) {
                    cmp = -1;
                }
                else if (timestamp > o.timestamp) {
                    cmp = 1;
                }
            }
            
            return cmp;
        }
    }
}
