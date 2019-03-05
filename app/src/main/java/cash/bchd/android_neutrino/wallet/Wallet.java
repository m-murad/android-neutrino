package cash.bchd.android_neutrino.wallet;

import android.content.Context;

import com.google.android.gms.common.util.ArrayUtils;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import walletrpc.Api;
import walletrpc.WalletLoaderServiceGrpc;
import walletrpc.WalletServiceGrpc;

/**
 * Wallet represents an instance of a bchwallet. It will load and start the
 * bchwallet daemon and provide convience methods to the wallet's API calls.
 */
public class Wallet implements Serializable {

    private static Wallet instance;

    private String getConfigFilePath;
    private final String host = "127.0.0.1";
    private final int port = 8332;
    private ManagedChannel channel;
    public static final io.grpc.Context.Key<String> AUTH_TOKEN_KEY = io.grpc.Context.key("AuthenticationToken");
    private AuthCredentials creds;

    public final static String DEFAULT_PASSPHRASE = "LETMEIN";

    private final String MAINNET_URI_PREFIX = "bitcoincash:";

    private HashMap<String, AddressListener> addressListeners = new HashMap<String, AddressListener>();
    private HashMap<ByteString, String[]> metadataCache = new HashMap<ByteString, String[]>();

    private int lastBlockHeight;

    private boolean running;

    /**
     * The wallet constructor takes in a context which it uses to derive the config file
     * path and appdatadir.
     */
    public Wallet(Context context, Config config) {
        this.getConfigFilePath = config.getConfigFilePath();
        this.creds = new AuthCredentials(config.getAuthToken());
        this.channel = ManagedChannelBuilder.forAddress(this.host, this.port).usePlaintext().build();
        instance = this;
        try {
            config.save(context);
        } catch (Exception e) {
            e.printStackTrace();
        }
        // TODO: if we ever add testnet then set the scheme here
        BitcoinPaymentURI.SCHEME = MAINNET_URI_PREFIX;
    }

    public static Wallet getInstance() {
        return instance;
    }

    public void loadWallet(WalletEventListener listener) throws Exception {
        if (!walletExists()) {
            String mnemonic = generateMnemonic();
            WalletLoaderServiceGrpc.WalletLoaderServiceFutureStub stub = WalletLoaderServiceGrpc.newFutureStub(this.channel).withCallCredentials(this.creds);
            ByteString pw = ByteString.copyFromUtf8(DEFAULT_PASSPHRASE);

            Api.CreateWalletRequest request = Api.CreateWalletRequest.newBuilder().setPrivatePassphrase(pw).setMnemonicSeed(mnemonic).build();
            stub.createWallet(request);
            listener.onWalletCreated(mnemonic);
        }

        Thread thread = new Thread(){
            public void run(){
                while (true) {
                    try {
                        WalletServiceGrpc.WalletServiceBlockingStub stub = WalletServiceGrpc.newBlockingStub(channel).withCallCredentials(creds);
                        Api.PingRequest request = Api.PingRequest.newBuilder().build();
                        stub.ping(request);
                        listener.onWalletReady();
                        listener.onBalanceChange(balance());
                        getTransactions(listener);
                        listenTransactions(listener);
                        running = true;
                        break;
                    } catch (Exception e) {}
                }
            }
        };
        thread.start();
    }

    public boolean isRunning() {
        return running;
    }

    public String uriPrefix() {
        return MAINNET_URI_PREFIX;
    }

    public void listenTransactions(WalletEventListener listener) {
        try {
            lastBlockHeight = network().getBestHeight();
            listener.onBlock(lastBlockHeight);
        } catch (Exception e) {
            e.printStackTrace();
        }

        WalletServiceGrpc.WalletServiceStub stub = WalletServiceGrpc.newStub(channel).withCallCredentials(creds);
        Api.TransactionNotificationsRequest request = Api.TransactionNotificationsRequest.newBuilder().build();
        stub.transactionNotifications(request, new StreamObserver<Api.TransactionNotificationsResponse>() {
            @Override
            public void onNext(Api.TransactionNotificationsResponse value) {
                List<Api.BlockDetails> blocks = value.getAttachedBlocksList();
                if (value.getDetachedBlocksCount() > 0) {
                    lastBlockHeight = 0;
                }
                boolean hasMinedTransactions = false;
                for (Api.BlockDetails block : blocks) {
                    if (block.getHeight() > lastBlockHeight) {
                        lastBlockHeight = block.getHeight();
                        listener.onBlock(block.getHeight());
                    }
                    if (block.getTransactionsCount() > 0) {
                        hasMinedTransactions = true;
                        for (Api.TransactionDetails tx : block.getTransactionsList()) {
                            for (Api.TransactionDetails.Output output : tx.getCreditsList()) {
                                AddressListener addrListener = (AddressListener) addressListeners.get(output.getAddress());
                                if (addrListener != null) {
                                    addrListener.onPaymentReceived(output.getAmount());
                                }
                            }
                            TransactionData td = extractTransactionData(tx, block.getHeight(), block.getTimestamp());
                            listener.onTransaction(td);
                        }
                    }
                }

                if (value.getUnminedTransactionsCount() > 0 || hasMinedTransactions) {
                    for (Api.TransactionDetails tx : value.getUnminedTransactionsList()) {
                        for (Api.TransactionDetails.Output output : tx.getCreditsList()) {
                            AddressListener addrListener = (AddressListener) addressListeners.get(output.getAddress());
                            if (addrListener != null) {
                                addrListener.onPaymentReceived(output.getAmount());
                            }
                        }
                        TransactionData td = extractTransactionData(tx, 0, System.currentTimeMillis()/1000);
                        listener.onTransaction(td);
                    }
                    try {
                        long bal = balance();
                        listener.onBalanceChange(bal);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                t.printStackTrace();
            }

            @Override
            public void onCompleted() {

            }
        });
    }

    public void listenAddress(String address, AddressListener listener) {
        addressListeners.put(address, listener);
    }

    public String currentAddress() throws Exception {
        WalletServiceGrpc.WalletServiceBlockingStub stub = WalletServiceGrpc.newBlockingStub(this.channel).withCallCredentials(this.creds);
        Api.CurrentAddressRequest request = Api.CurrentAddressRequest.newBuilder().build();
        Api.CurrentAddressResponse reply = stub.currentAddress(request);
        return reply.getAddress();
    }

    public Api.CreateTransactionResponse createTransaction(String addr, long amtSatoshi, int feePerByte) throws Exception {
        WalletServiceGrpc.WalletServiceBlockingStub stub = WalletServiceGrpc.newBlockingStub(this.channel).withCallCredentials(this.creds);
        Api.CreateTransactionRequest.Output output = Api.CreateTransactionRequest.Output.newBuilder().setAmount(amtSatoshi).setAddress(addr).build();
        Api.CreateTransactionRequest request = Api.CreateTransactionRequest.newBuilder()
                .setAccount(0)
                .setRequiredConfirmations(0)
                .setSatPerKbFee(feePerByte*1000)
                .addOutputs(output).build();
        Api.CreateTransactionResponse reply = stub.createTransaction(request);
        return reply;
    }

    public byte[] signTransaction(byte[] serializedTx, List<Long> inputValues, String passphrase) throws Exception {
        WalletServiceGrpc.WalletServiceBlockingStub stub = WalletServiceGrpc.newBlockingStub(this.channel).withCallCredentials(this.creds);
        Api.SignTransactionRequest.Builder builder = Api.SignTransactionRequest.newBuilder();
        builder.setSerializedTransaction(ByteString.copyFrom(serializedTx));
        for (Long val : inputValues) {
            builder.addInputValues(val);
        }
        ByteString pw = ByteString.copyFromUtf8(passphrase);
        builder.setPassphrase(pw);
        Api.SignTransactionRequest request = builder.build();

        Api.SignTransactionResponse reply = stub.signTransaction(request);
        if (reply.getUnsignedInputIndexesList().size() > 0) {
            throw new Exception("Error signing transaction");
        }
        return reply.getTransaction().toByteArray();
    }

    public void publishTransaction(byte[] serializedTransaction, String toAddress, String memo) throws Exception {
        ByteString bs = ByteString.copyFrom(serializedTransaction);
        String[] metadata = new String[2];
        metadata[0] = toAddress;
        metadata[1] = memo;
        this.metadataCache.put(bs, metadata);
        WalletServiceGrpc.WalletServiceBlockingStub stub = WalletServiceGrpc.newBlockingStub(this.channel).withCallCredentials(this.creds);
        Api.PublishTransactionRequest request = Api.PublishTransactionRequest.newBuilder().setSignedTransaction(bs).build();
        Api.PublishTransactionResponse reply = stub.publishTransaction(request);
    }

    public Api.SweepAccountResponse sweepAccount(String addr, int feePerByte) throws Exception {
        WalletServiceGrpc.WalletServiceBlockingStub stub = WalletServiceGrpc.newBlockingStub(this.channel).withCallCredentials(this.creds);
        Api.SweepAccountRequest request = Api.SweepAccountRequest.newBuilder()
                .setAccount(0).setSatPerKbFee(feePerByte*1000).setSweepToAddress(addr).build();
        Api.SweepAccountResponse reply = stub.sweepAccount(request);
        return reply;
    }

    public long balance() throws Exception {
        WalletServiceGrpc.WalletServiceBlockingStub stub = WalletServiceGrpc.newBlockingStub(this.channel).withCallCredentials(this.creds);
        Api.BalanceRequest request = Api.BalanceRequest.newBuilder().setAccountNumber(0).setRequiredConfirmations(0).build();
        Api.BalanceResponse reply = stub.balance(request);
        return reply.getSpendable();
    }

    public boolean validateAddress(String addr) throws Exception {
        WalletServiceGrpc.WalletServiceBlockingStub stub = WalletServiceGrpc.newBlockingStub(this.channel).withCallCredentials(this.creds);
        Api.ValidateAddressRequest request = Api.ValidateAddressRequest.newBuilder().setAddress(addr).build();
        Api.ValidateAddressResponse reply = stub.validateAddress(request);
        return reply.getValid();
    }

    public Api.NetworkResponse network() throws Exception {
        WalletServiceGrpc.WalletServiceBlockingStub stub = WalletServiceGrpc.newBlockingStub(channel).withCallCredentials(creds);
        Api.NetworkRequest request = Api.NetworkRequest.newBuilder().build();
        Api.NetworkResponse reply = stub.network(request);
        return reply;
    }

    private boolean walletExists() throws Exception {
        WalletLoaderServiceGrpc.WalletLoaderServiceBlockingStub stub = WalletLoaderServiceGrpc.newBlockingStub(this.channel).withCallCredentials(this.creds);
        Api.WalletExistsRequest request = Api.WalletExistsRequest.newBuilder().build();
        Api.WalletExistsResponse reply = stub.walletExists(request);
        return reply.getExists();
    }

    private String generateMnemonic() throws Exception {
        WalletLoaderServiceGrpc.WalletLoaderServiceBlockingStub stub = WalletLoaderServiceGrpc.newBlockingStub(this.channel).withCallCredentials(this.creds);
        Api.GenerateMnemonicSeedRequest request = Api.GenerateMnemonicSeedRequest.newBuilder().setBitSize(128).build();
        Api.GenerateMnemonicSeedResponse reply = stub.generateMnemonicSeed(request);
        return reply.getMnemonic();
    }

    public void getTransactions(WalletEventListener listener) throws Exception {
        WalletServiceGrpc.WalletServiceFutureStub stub = WalletServiceGrpc.newFutureStub(this.channel).withCallCredentials(this.creds);
        Api.GetTransactionsRequest request = Api.GetTransactionsRequest.newBuilder().build();
        ListenableFuture<Api.GetTransactionsResponse> reply = stub.getTransactions(request);

        Futures.addCallback(reply, new FutureCallback<Api.GetTransactionsResponse>() {
            @Override
            public void onSuccess(Api.GetTransactionsResponse result) {
                int bestHeight = 0;
                try {
                    bestHeight = network().getBestHeight();
                } catch (Exception e) {
                    e.printStackTrace();
                }

                List<TransactionData> txs = new ArrayList<TransactionData>();
                for (Api.BlockDetails block : result.getMinedTransactionsList()) {
                    for (Api.TransactionDetails tx : block.getTransactionsList()) {
                        txs.add(extractTransactionData(tx, block.getHeight(), block.getTimestamp()));
                    }
                }
                for (Api.TransactionDetails tx : result.getUnminedTransactionsList()) {
                    txs.add(extractTransactionData(tx, 0, System.currentTimeMillis()/1000));
                }
                listener.onGetTransactions(txs, bestHeight);
            }

            @Override
            public void onFailure(Throwable t) {
                t.printStackTrace();
            }
        });
    }

    private TransactionData extractTransactionData(Api.TransactionDetails tx, int height, long timestamp) {
        long total = 0;
        String toAddress = "";
        for (Api.TransactionDetails.Output output : tx.getCreditsList()) {
            total += output.getAmount();
            toAddress = output.getAddress();
        }
        for (Api.TransactionDetails.Input input : tx.getDebitsList()) {
            total -= input.getPreviousAmount();
        }
        if (total > 0) {
            toAddress = "";
        }

        byte[] txBytes = tx.getHash().toByteArray();
        reverse(txBytes);
        String txid = BaseEncoding.base16().lowerCase().encode(txBytes);

        String[] metaData = this.metadataCache.get(tx.getTransaction());
        String memo = "";
        if (metaData != null && metaData.length == 2) {
            toAddress = metaData[0];
            memo = metaData[1];
            this.metadataCache.remove(tx.getHash());
        }

        TransactionData txData = new TransactionData(txid, (total > 0), memo, total, "", "", timestamp, toAddress, height);
        return txData;
    }

    /**
     * Start will start the bchwallet daemon with the requested config options
     */
    public void start() {
        mobile.Mobile.startWallet(this.getConfigFilePath);
    }

    /**
     * Stop cleanly shuts down the wallet daemon. This must be called on close to guarantee
     * no data is corrupted.
     */
    public void stop() {
        if (isRunning()) {
            channel.shutdown();
        }
        mobile.Mobile.stopWallet();
    }

    public static void reverse(byte[] array) {
        if (array == null) {
            return;
        }
        int i = 0;
        int j = array.length - 1;
        byte tmp;
        while (j > i) {
            tmp = array[j];
            array[j] = array[i];
            array[i] = tmp;
            j--;
            i++;
        }
    }
}
