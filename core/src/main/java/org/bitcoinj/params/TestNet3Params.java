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

package org.bitcoinj.params;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Difficulty;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;

import java.time.Instant;

import static org.bitcoinj.base.internal.Preconditions.checkState;

/**
 * Parameters for the testnet, a separate public instance of Bitcoin that has relaxed rules suitable for development
 * and testing of applications and new Bitcoin versions.
 */
public class TestNet3Params extends BitcoinNetworkParams {
    public static final int TESTNET_MAJORITY_WINDOW = 100;
    public static final int TESTNET_MAJORITY_REJECT_BLOCK_OUTDATED = 75;
    public static final int TESTNET_MAJORITY_ENFORCE_BLOCK_UPGRADE = 51;
    private static final Instant GENESIS_TIME = Instant.ofEpochSecond(1296688602);
    private static final long GENESIS_NONCE = 414098458;
    private static final Sha256Hash GENESIS_HASH = Sha256Hash.wrap("000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943");
    /** Spacing for the 20-minute difficulty exception. */
    private static final int TESTNET_DIFFICULTY_EXCEPTION_SPACING = NetworkParameters.TARGET_SPACING * 2;

    public TestNet3Params() {
        super(BitcoinNetwork.TESTNET);

        targetTimespan = TARGET_TIMESPAN;
        maxTarget = Difficulty.STANDARD_MAX_DIFFICULTY_TARGET;

        port = 18333;
        packetMagic = 0x0b110907;
        dumpedPrivateKeyHeader = 239;
        spendableCoinbaseDepth = 100;
        bip32HeaderP2PKHpub = 0x043587cf; // The 4 byte header that serializes in base58 to "tpub".
        bip32HeaderP2PKHpriv = 0x04358394; // The 4 byte header that serializes in base58 to "tprv"
        bip32HeaderP2WPKHpub = 0x045f1cf6; // The 4 byte header that serializes in base58 to "vpub".
        bip32HeaderP2WPKHpriv = 0x045f18bc; // The 4 byte header that serializes in base58 to "vprv"

        majorityEnforceBlockUpgrade = TESTNET_MAJORITY_ENFORCE_BLOCK_UPGRADE;
        majorityRejectBlockOutdated = TESTNET_MAJORITY_REJECT_BLOCK_OUTDATED;
        majorityWindow = TESTNET_MAJORITY_WINDOW;

        dnsSeeds = new String[] {
                "testnet-seed.bitcoin.jonasschnelli.ch", // Jonas Schnelli
                "seed.tbtc.petertodd.net",               // Peter Todd
                "seed.testnet.bitcoin.sprovoost.nl",     // Sjors Provoost
                "testnet-seed.bluematt.me",              // Matt Corallo
                "seed.testnet.achownodes.xyz",           // Ava Chow
        };
        addrSeeds = null;

    }

    private static TestNet3Params instance;
    public static synchronized TestNet3Params get() {
        if (instance == null) {
            instance = new TestNet3Params();
        }
        return instance;
    }

    @Override
    public Block getGenesisBlock() {
        synchronized (GENESIS_HASH) {
            if (genesisBlock == null) {
                genesisBlock = Block.createGenesis(GENESIS_TIME, Difficulty.STANDARD_MAX_DIFFICULTY_TARGET,
                        GENESIS_NONCE);
                checkState(genesisBlock.getHash().equals(GENESIS_HASH), () ->
                        "invalid genesis hash");
            }
        }
        return genesisBlock;
    }

    // February 16th 2012
    private static final Instant testnetDiffDate = Instant.ofEpochMilli(1329264000000L);

    @Override
    public void checkDifficultyTransitions(final StoredBlock storedPrev, final Block nextBlock,
        final BlockStore blockStore) throws VerificationException, BlockStoreException {
        if (!isDifficultyTransitionPoint(storedPrev.getHeight()) && nextBlock.time().isAfter(testnetDiffDate)) {
            // After 15th February 2012 the rules on the testnet change to avoid people running up the difficulty
            // and then leaving, making it too hard to mine a block. On non-difficulty transition points, easy
            // blocks are allowed if there has been a span of 20 minutes without one.
            long timeDelta = nextBlock.time().getEpochSecond() - storedPrev.getHeader().time().getEpochSecond();
            boolean isMinDiffBlock = nextBlock.difficultyTarget().equals(maxTarget());
            if (timeDelta < 0 && isMinDiffBlock) {
                // There is an integer underflow bug in Bitcoin Core that means mindiff blocks are accepted when time
                // goes backwards. Thus, skip any further checks.
                return;
            } else if (timeDelta > TESTNET_DIFFICULTY_EXCEPTION_SPACING){
                // 20 minute exception
                checkDifficultyTarget(nextBlock, maxTarget());
            } else {
                // If no special rule applies, expect the last non-mindiff difficulty.
                checkDifficultyTarget(nextBlock, backwardsSkipMindiffBlocks(storedPrev, blockStore).difficultyTarget());
            }
        } else {
            super.checkDifficultyTransitions(storedPrev, nextBlock, blockStore);
        }
    }

    private void checkDifficultyTarget(Block nextBlock, Difficulty expectedTarget) {
        Difficulty newTarget = nextBlock.difficultyTarget();
        if (!newTarget.equals(expectedTarget))
            throw new VerificationException("Testnet block transition that is not allowed: " +
                    expectedTarget + " vs " +
                    newTarget);
    }

    private Block backwardsSkipMindiffBlocks(StoredBlock prev, BlockStore blockStore) throws BlockStoreException {
        // Walk backwards until we find a block that doesn't have the easiest proof of work.
        int interval = getInterval();
        Difficulty maxTarget = maxTarget();
        while (!prev.getHeader().equals(getGenesisBlock()) &&
                prev.getHeight() % interval != 0 &&
                prev.getHeader().difficultyTarget().equals(maxTarget))
            prev = prev.getPrev(blockStore);
        return prev.getHeader();
    }
}
