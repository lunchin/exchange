/*
 * This file is part of bisq.
 *
 * bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bisq.core.dao.blockchain.parse;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.neemre.btcdcli4j.core.BitcoindException;
import com.neemre.btcdcli4j.core.CommunicationException;
import com.neemre.btcdcli4j.core.client.BtcdClient;
import com.neemre.btcdcli4j.core.client.BtcdClientImpl;
import com.neemre.btcdcli4j.core.domain.Block;
import com.neemre.btcdcli4j.core.domain.RawTransaction;
import com.neemre.btcdcli4j.core.domain.enums.ScriptTypes;
import com.neemre.btcdcli4j.daemon.BtcdDaemon;
import com.neemre.btcdcli4j.daemon.BtcdDaemonImpl;
import com.neemre.btcdcli4j.daemon.event.BlockListener;
import io.bisq.core.dao.DaoOptionKeys;
import io.bisq.core.dao.blockchain.btcd.PubKeyScript;
import io.bisq.core.dao.blockchain.exceptions.BsqBlockchainException;
import io.bisq.core.dao.blockchain.vo.Tx;
import io.bisq.core.dao.blockchain.vo.TxInput;
import io.bisq.core.dao.blockchain.vo.TxOutput;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.bitcoinj.core.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import java.io.*;
import java.net.URL;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;

// Blocking access to Bitcoin Core via RPC requests
// See the rpc.md file in the doc directory for more info about the setup.
public class RpcService {
    private static final Logger log = LoggerFactory.getLogger(RpcService.class);

    private final String rpcUser;
    private final String rpcPassword;
    private final String rpcPort;
    private final String rpcBlockPort;
    private boolean dumpBlockchainData;

    private BtcdClient client;
    private BtcdDaemon daemon;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @SuppressWarnings("WeakerAccess")
    @Inject
    public RpcService(@Named(DaoOptionKeys.RPC_USER) String rpcUser,
                      @Named(DaoOptionKeys.RPC_PASSWORD) String rpcPassword,
                      @Named(DaoOptionKeys.RPC_PORT) String rpcPort,
                      @Named(DaoOptionKeys.RPC_BLOCK_NOTIFICATION_PORT) String rpcBlockPort,
                      @Named(DaoOptionKeys.DUMP_BLOCKCHAIN_DATA) boolean dumpBlockchainData) {
        this.rpcUser = rpcUser;
        this.rpcPassword = rpcPassword;
        this.rpcPort = rpcPort;
        this.rpcBlockPort = rpcBlockPort;
        this.dumpBlockchainData = dumpBlockchainData;
    }

    void setup() throws BsqBlockchainException {
        long startTs = System.currentTimeMillis();
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        CloseableHttpClient httpProvider = HttpClients.custom().setConnectionManager(cm).build();
        Properties nodeConfig = new Properties();
        URL resource = getClass().getClassLoader().getResource("btcRpcConfig.properties");
        checkNotNull(resource, "btcRpcConfig.properties not found");
        try (FileInputStream fileInputStream = new FileInputStream(new File(resource.toURI()))) {
            try (InputStream inputStream = new BufferedInputStream(fileInputStream)) {
                nodeConfig.load(inputStream);
                nodeConfig.setProperty("node.bitcoind.rpc.user", rpcUser);
                nodeConfig.setProperty("node.bitcoind.rpc.password", rpcPassword);
                nodeConfig.setProperty("node.bitcoind.rpc.port", rpcPort);
                nodeConfig.setProperty("node.bitcoind.notification.block.port", rpcBlockPort);
                BtcdClientImpl client = new BtcdClientImpl(httpProvider, nodeConfig);
                daemon = new BtcdDaemonImpl(client);
                log.info("Setup took {} ms", System.currentTimeMillis() - startTs);
                this.client = client;
            } catch (IOException | BitcoindException | CommunicationException e) {
                if (e instanceof CommunicationException)
                    log.error("Maybe the rpc port is not set correctly? rpcPort=" + rpcPort);
                log.error(e.toString());
                e.printStackTrace();
                log.error(e.getCause() != null ? e.getCause().toString() : "e.getCause()=null");
                throw new BsqBlockchainException(e.getMessage(), e);
            }
        } catch (Throwable e) {
            log.error(e.toString());
            e.printStackTrace();
            throw new BsqBlockchainException(e.toString(), e);
        }
    }

    void registerBlockHandler(Consumer<Block> blockHandler) {
        daemon.addBlockListener(new BlockListener() {
            @Override
            public void blockDetected(Block block) {
                if (block != null) {
                    log.info("New block received: height={}, id={}", block.getHeight(), block.getHash());
                    blockHandler.accept(block);
                } else {
                    log.error("We received a block with value null. That should not happen.");
                }
            }
        });
    }

    int requestChainHeadHeight() throws BitcoindException, CommunicationException {
        return client.getBlockCount();
    }

    Block requestBlock(int blockHeight) throws BitcoindException, CommunicationException {
        final String blockHash = client.getBlockHash(blockHeight);
        return client.getBlock(blockHash);
    }

    Tx requestTransaction(String txId, int blockHeight) throws BsqBlockchainException {
        try {
            RawTransaction rawTransaction = requestRawTransaction(txId);
            // rawTransaction.getTime() is in seconds but we keep it in ms internally
            final long time = rawTransaction.getTime() * 1000;
            final List<TxInput> txInputs = rawTransaction.getVIn()
                    .stream()
                    .filter(rawInput -> rawInput != null && rawInput.getVOut() != null && rawInput.getTxId() != null)
                    .map(rawInput -> new TxInput(rawInput.getVOut(), rawInput.getTxId()))
                    .collect(Collectors.toList());

            final List<TxOutput> txOutputs = rawTransaction.getVOut()
                    .stream()
                    .filter(e -> e != null && e.getN() != null && e.getValue() != null && e.getScriptPubKey() != null)
                    .map(rawOutput -> {
                                byte[] opReturnData = null;
                                final com.neemre.btcdcli4j.core.domain.PubKeyScript scriptPubKey = rawOutput.getScriptPubKey();
                                if (scriptPubKey.getType().equals(ScriptTypes.NULL_DATA)) {
                                    String[] chunks = scriptPubKey.getAsm().split(" ");
                                    if (chunks.length == 2 && chunks[0].equals("OP_RETURN")) {
                                        opReturnData = Utils.HEX.decode(chunks[1]);
                                    }
                                }
                                // We dont support raw MS which are the only case where scriptPubKey.getAddresses()>1
                                String address = scriptPubKey.getAddresses() != null &&
                                        scriptPubKey.getAddresses().size() == 1 ? scriptPubKey.getAddresses().get(0) : null;
                                final PubKeyScript pubKeyScript = dumpBlockchainData ? new PubKeyScript(scriptPubKey) : null;
                                return new TxOutput(rawOutput.getN(),
                                        rawOutput.getValue().movePointRight(8).longValue(),
                                        rawTransaction.getTxId(),
                                        pubKeyScript,
                                        address,
                                        opReturnData,
                                        blockHeight,
                                        time);
                            }
                    )
                    .collect(Collectors.toList());

            return new Tx(txId,
                    blockHeight,
                    rawTransaction.getBlockHash(),
                    ImmutableList.copyOf(txInputs),
                    ImmutableList.copyOf(txOutputs),
                    false);
        } catch (BitcoindException | CommunicationException e) {
            throw new BsqBlockchainException(e.getMessage(), e);
        }
    }

    RawTransaction requestRawTransaction(String txId) throws BitcoindException, CommunicationException {
        return (RawTransaction) client.getRawTransaction(txId, 1);
    }
}