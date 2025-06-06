/*
 * Copyright 2013 Google Inc.
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.tools;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.internal.TimeUtils;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.CheckpointManager;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.MemoryBlockStore;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.utils.Threading;
import picocli.CommandLine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.bitcoinj.base.internal.Preconditions.checkState;

/**
 * Downloads and verifies a full chain from your local peer, emitting checkpoints at each difficulty transition period
 * to a file which is then signed with your key.
 */
@CommandLine.Command(name = "build-checkpoints", usageHelpAutoWidth = true, sortOptions = false, description = "Create checkpoint files to use with CheckpointManager.")
public class BuildCheckpoints implements Callable<Integer> {
    @CommandLine.Option(names = "--net", description = "Which network to connect to. Valid values: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}")
    private BitcoinNetwork net = BitcoinNetwork.MAINNET;
    @CommandLine.Option(names = "--peer", description = "IP address/domain name for connection instead of localhost.")
    private String peer = null;
    @CommandLine.Option(names = "--days", description = "How many days to keep as a safety margin. Checkpointing will be done up to this many days ago.")
    private int days = 7;
    @CommandLine.Option(names = "--verbose", description = "Enables logging.")
    private boolean verbose = false;
    @CommandLine.Option(names = "--help", usageHelp = true, description = "Displays program options.")
    private boolean help;

    private static NetworkParameters params;

    public static void main(String[] args) throws Exception {
        int exitCode = new CommandLine(new BuildCheckpoints()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        if (verbose)
            BriefLogFormatter.init();
        else
            BriefLogFormatter.initWithSilentBitcoinJ();

        final String suffix;
        params = NetworkParameters.of(net);
        Context.propagate(new Context());

        switch (net) {
            case MAINNET:
                suffix = "";
                break;
            case TESTNET:
                suffix = "-testnet";
                break;
            case SIGNET:
                suffix = "-signet";
                break;
            case REGTEST:
                suffix = "-regtest";
                break;
            default:
                throw new RuntimeException("Unreachable.");
        }

        // Configure bitcoinj to fetch only headers, not save them to disk, connect to a local fully synced/validated
        // node and to save block headers that are on interval boundaries, as long as they are <1 month old.
        final BlockStore store = new MemoryBlockStore(params.getGenesisBlock());
        final BlockChain chain = new BlockChain(net, store);
        final PeerGroup peerGroup = new PeerGroup(net, chain);

        final InetAddress ipAddress;

        // DNS discovery can be used for some networks
        boolean networkHasDnsSeeds = params.getDnsSeeds() != null;
        if (peer != null) {
            // use peer provided in argument
            try {
                ipAddress = InetAddress.getByName(peer);
                startPeerGroup(peerGroup, ipAddress);
            } catch (UnknownHostException e) {
                System.err.println("Could not understand peer domain name/IP address: " + peer + ": " + e.getMessage());
                return 1;
            }
        } else if (networkHasDnsSeeds) {
            // use a peer group discovered with dns
            peerGroup.setUserAgent("PeerMonitor", "1.0");
            peerGroup.setMaxConnections(20);
            peerGroup.addPeerDiscovery(new DnsDiscovery(net));
            peerGroup.start();

            // Connect to at least 4 peers because some may not support download
            Future<List<Peer>> future = peerGroup.waitForPeers(4);
            System.out.println("Connecting to " + params.getId() + ", timeout 20 seconds...");
            // throw timeout exception if we can't get peers
            future.get(20, SECONDS);
        } else {
            // try localhost
            ipAddress = InetAddress.getLocalHost();
            startPeerGroup(peerGroup, ipAddress);
        }

        // Sorted map of block height to StoredBlock object.
        final TreeMap<Integer, StoredBlock> checkpoints = new TreeMap<>();

        Instant now = TimeUtils.currentTime();
        peerGroup.setFastCatchupTime(now);

        Instant timeAgo = now.minus(days, ChronoUnit.DAYS);
        System.out.println("Checkpointing up to " + TimeUtils.dateTimeFormat(timeAgo));

        chain.addNewBestBlockListener(Threading.SAME_THREAD, block -> {
            int height = block.getHeight();
            if (height % params.getInterval() == 0 && timeAgo.isAfter(block.getHeader().time())) {
                System.out.println(String.format("Checkpointing block %s at height %d, time %s",
                        block.getHeader().getHash(), block.getHeight(),
                        TimeUtils.dateTimeFormat(block.getHeader().time())));
                checkpoints.put(height, block);
            }
        });

        peerGroup.downloadBlockChain();

        checkState(checkpoints.size() > 0);

        final File textFile = new File("checkpoints" + suffix + ".txt");

        // Write checkpoint data out.
        writeTextualCheckpoints(checkpoints, textFile);

        peerGroup.stop();
        store.close();

        // Sanity check the created files.
        sanityCheck(textFile, checkpoints.size());

        return 0;
    }

    private static final BigInteger MAX_WORK_V1 = new BigInteger(/* 12 bytes */ "ffffffffffffffffffffffff", 16);

    private static void writeTextualCheckpoints(TreeMap<Integer, StoredBlock> checkpoints, File file)
            throws IOException {
        try (PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.US_ASCII))) {
            writer.println("TXT CHECKPOINTS 1");
            writer.println("0"); // Number of signatures to read. Do this later.
            writer.println(checkpoints.size());
            ByteBuffer bufferV1 = ByteBuffer.allocate(StoredBlock.COMPACT_SERIALIZED_SIZE);
            ByteBuffer bufferV2 = ByteBuffer.allocate(StoredBlock.COMPACT_SERIALIZED_SIZE_V2);
            for (StoredBlock block : checkpoints.values()) {
                if (block.getChainWork().compareTo(MAX_WORK_V1) <= 0) {
                    ((Buffer) bufferV1).rewind();
                    block.serializeCompact(bufferV1);
                    writer.println(CheckpointManager.BASE64.encode(bufferV1.array()));
                } else {
                    ((Buffer) bufferV2).rewind();
                    block.serializeCompactV2(bufferV2);
                    writer.println(CheckpointManager.BASE64.encode(bufferV2.array()));
                }
            }
            System.out.println("Checkpoints written to '" + file.getCanonicalPath() + "'.");
        }
    }

    private static void sanityCheck(File file, int expectedSize) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        CheckpointManager manager;
        try {
            manager = new CheckpointManager(params, fis);
        } finally {
            fis.close();
        }

        checkState(manager.numCheckpoints() == expectedSize);

        if (params.network() == BitcoinNetwork.MAINNET) {
            StoredBlock test = manager.getCheckpointBefore(Instant.ofEpochSecond(1390500000)); // Thu Jan 23 19:00:00 CET 2014
            checkState(test.getHeight() == 280224);
            checkState(test.getHeader().getHashAsString()
                    .equals("00000000000000000b5d59a15f831e1c45cb688a4db6b0a60054d49a9997fa34"));
        } else if (params.network() == BitcoinNetwork.TESTNET) {
            StoredBlock test = manager.getCheckpointBefore(Instant.ofEpochSecond(1390500000)); // Thu Jan 23 19:00:00 CET 2014
            checkState(test.getHeight() == 167328);
            checkState(test.getHeader().getHashAsString()
                    .equals("0000000000035ae7d5025c2538067fe7adb1cf5d5d9c31b024137d9090ed13a9"));
        } else if (params.network() == BitcoinNetwork.SIGNET) {
            StoredBlock test = manager.getCheckpointBefore(Instant.ofEpochSecond(1642000000)); // 2022-01-12
            checkState(test.getHeight() == 72576);
            checkState(test.getHeader().getHashAsString()
                    .equals("0000008f763bdf23bd159a21ccf211098707671d2ca9aa72d0f586c24505c5e7"));
        }
    }

    private static void startPeerGroup(PeerGroup peerGroup, InetAddress ipAddress) {
        final PeerAddress peerAddress = PeerAddress.simple(ipAddress, params.getPort());
        System.out.println("Connecting to " + peerAddress + "...");
        peerGroup.addAddress(peerAddress);
        peerGroup.start();
    }
}
