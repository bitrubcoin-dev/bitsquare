package io.bitsquare.p2p.peers;

import io.bitsquare.app.Log;
import io.bitsquare.common.Clock;
import io.bitsquare.common.Timer;
import io.bitsquare.common.UserThread;
import io.bitsquare.p2p.NodeAddress;
import io.bitsquare.p2p.network.*;
import io.bitsquare.p2p.peers.peerexchange.Peer;
import io.bitsquare.storage.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.File;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class PeerManager implements ConnectionListener {
    ///////////////////////////////////////////////////////////////////////////////////////////
    // Static
    ///////////////////////////////////////////////////////////////////////////////////////////
    private static final Logger log = LoggerFactory.getLogger(PeerManager.class);

    private static final long CHECK_MAX_CONN_DELAY_SEC = 5;
    // Use a long delay as the bootstrapping peer might need a while until it knows its onion address
    private static final long REMOVE_ANONYMOUS_PEER_SEC = 120;

    private static final int MAX_REPORTED_PEERS = 1000;
    private static final int MAX_PERSISTED_PEERS = 500;
    private static final long MAX_AGE = TimeUnit.DAYS.toMillis(14); // max age for reported peers is 14 days

    private final boolean printReportedPeersDetails = true;
    private boolean lostAllConnections;

    private int maxConnections;
    private int minConnections;
    private int maxConnectionsPeer;
    private int maxConnectionsNonDirect;
    private int maxConnectionsAbsolute;

    // Modify this to change the relationships between connection limits.
    private void setConnectionLimits(int maxConnections) {
        this.maxConnections = maxConnections;
        minConnections = Math.max(1, maxConnections - 4);
        maxConnectionsPeer = maxConnections + 4;
        maxConnectionsNonDirect = maxConnections + 8;
        maxConnectionsAbsolute = maxConnections + 18;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Listener
    ///////////////////////////////////////////////////////////////////////////////////////////


    public interface Listener {
        void onAllConnectionsLost();

        void onNewConnectionAfterAllConnectionsLost();

        void onAwakeFromStandby();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Instance fields
    ///////////////////////////////////////////////////////////////////////////////////////////


    private final NetworkNode networkNode;
    private Clock clock;
    private final Set<NodeAddress> seedNodeAddresses;
    private final Storage<HashSet<Peer>> dbStorage;

    private final HashSet<Peer> persistedPeers = new HashSet<>();
    private final Set<Peer> reportedPeers = new HashSet<>();
    private Timer checkMaxConnectionsTimer;
    private final Clock.Listener listener;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private boolean stopped;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public PeerManager(NetworkNode networkNode, int maxConnections, Set<NodeAddress> seedNodeAddresses,
                       File storageDir, Clock clock) {
        setConnectionLimits(maxConnections);
        this.networkNode = networkNode;
        this.clock = clock;
        // seedNodeAddresses can be empty (in case there is only 1 seed node, the seed node starting up has no other seed nodes)
        this.seedNodeAddresses = new HashSet<>(seedNodeAddresses);
        networkNode.addConnectionListener(this);
        dbStorage = new Storage<>(storageDir);
        HashSet<Peer> persistedPeers = dbStorage.initAndGetPersisted("PersistedPeers");
        if (persistedPeers != null) {
            log.info("We have persisted reported peers. persistedPeers.size()=" + persistedPeers.size());
            this.persistedPeers.addAll(persistedPeers);
        }

        // we check if app was idle for more then 5 sec.
        listener = new Clock.Listener() {
            @Override
            public void onSecondTick() {
            }

            @Override
            public void onMinuteTick() {
            }

            @Override
            public void onMissedSecondTick(long missed) {
                if (missed > Clock.IDLE_TOLERANCE) {
                    log.warn("We have been in standby mode for {} sec", missed / 1000);
                    stopped = false;
                    listeners.stream().forEach(Listener::onAwakeFromStandby);
                }
            }
        };
        clock.addListener(listener);
    }

    public void shutDown() {
        Log.traceCall();

        networkNode.removeConnectionListener(this);
        clock.removeListener(listener);
        stopCheckMaxConnectionsTimer();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public int getMaxConnections() {
        return maxConnectionsAbsolute;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // ConnectionListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onConnection(Connection connection) {
        Log.logIfStressTests("onConnection to peer " +
                (connection.getPeersNodeAddressOptional().isPresent() ? connection.getPeersNodeAddressOptional().get() : "PeersNode unknown") +
                " / Nr. of connections: " + networkNode.getAllConnections().size());
        if (isSeedNode(connection))
            connection.setPeerType(Connection.PeerType.SEED_NODE);

        doHouseKeeping();

        if (lostAllConnections) {
            lostAllConnections = false;
            stopped = false;
            listeners.stream().forEach(Listener::onNewConnectionAfterAllConnectionsLost);
        }
    }

    @Override
    public void onDisconnect(CloseConnectionReason closeConnectionReason, Connection connection) {
        Log.logIfStressTests("onDisconnect of peer " +
                (connection.getPeersNodeAddressOptional().isPresent() ? connection.getPeersNodeAddressOptional().get() : "PeersNode unknown") +
                " / Nr. of connections: " + networkNode.getAllConnections().size() +
                " / closeConnectionReason: " + closeConnectionReason);
        handleConnectionFault(connection);

        lostAllConnections = networkNode.getAllConnections().isEmpty();
        if (lostAllConnections) {
            stopped = true;
            listeners.stream().forEach(Listener::onAllConnectionsLost);
        }
    }

    @Override
    public void onError(Throwable throwable) {
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Housekeeping
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void doHouseKeeping() {
        if (checkMaxConnectionsTimer == null) {
            printConnectedPeers();
            checkMaxConnectionsTimer = UserThread.runAfter(() -> {
                stopCheckMaxConnectionsTimer();
                if (!stopped) {
                    removeAnonymousPeers();
                    removeSuperfluousSeedNodes();
                    removeTooOldReportedPeers();
                    removeTooOldPersistedPeers();
                    checkMaxConnections(maxConnections);
                } else {
                    log.warn("We have stopped already. We ignore that checkMaxConnectionsTimer.run call.");
                }
            }, CHECK_MAX_CONN_DELAY_SEC);

        }
    }

    private boolean checkMaxConnections(int limit) {
        Log.traceCall("limit=" + limit);
        Set<Connection> allConnections = networkNode.getAllConnections();
        int size = allConnections.size();
        log.info("We have {} connections open. Our limit is {}", size, limit);

        if (size > limit) {
            log.info("We have too many connections open.\n\t" +
                    "Lets try first to remove the inbound connections of type PEER.");
            List<Connection> candidates = allConnections.stream()
                    .filter(e -> e instanceof InboundConnection)
                    .filter(e -> e.getPeerType() == Connection.PeerType.PEER)
                    .collect(Collectors.toList());

            if (candidates.size() == 0) {
                log.info("No candidates found. We check if we exceed our " +
                        "maxConnectionsPeer limit of {}", maxConnectionsPeer);
                if (size > maxConnectionsPeer) {
                    log.info("Lets try to remove ANY connection of type PEER.");
                    candidates = allConnections.stream()
                            .filter(e -> e.getPeerType() == Connection.PeerType.PEER)
                            .collect(Collectors.toList());

                    if (candidates.size() == 0) {
                        log.info("No candidates found. We check if we exceed our " +
                                "maxConnectionsNonDirect limit of {}", maxConnectionsNonDirect);
                        if (size > maxConnectionsNonDirect) {
                            log.info("Lets try to remove any connection which is not of type DIRECT_MSG_PEER.");
                            candidates = allConnections.stream()
                                    .filter(e -> e.getPeerType() != Connection.PeerType.DIRECT_MSG_PEER)
                                    .collect(Collectors.toList());

                            if (candidates.size() == 0) {
                                log.info("No candidates found. We check if we exceed our " +
                                        "maxConnectionsAbsolute limit of {}", maxConnectionsAbsolute);
                                if (size > maxConnectionsAbsolute) {
                                    log.info("Lets try to remove any connection.");
                                    candidates = allConnections.stream().collect(Collectors.toList());
                                }
                            }
                        }
                    }
                }
            }

            if (candidates.size() > 0) {
                candidates.sort((o1, o2) -> ((Long) o1.getStatistic().getLastActivityTimestamp()).compareTo(((Long) o2.getStatistic().getLastActivityTimestamp())));
                log.info("Candidates.size() for shut down=" + candidates.size());
                Connection connection = candidates.remove(0);
                log.info("We are going to shut down the oldest connection.\n\tconnection=" + connection.toString());
                if (!connection.isStopped())
                    connection.shutDown(CloseConnectionReason.TOO_MANY_CONNECTIONS_OPEN, () -> checkMaxConnections(limit));
                return true;
            } else {
                log.warn("No candidates found to remove (That case should not be possible as we use in the " +
                        "last case all connections).\n\t" +
                        "allConnections=", allConnections);
                return false;
            }
        } else {
            log.trace("We only have {} connections open and don't need to close any.", size);
            return false;
        }
    }

    private void removeAnonymousPeers() {
        Log.traceCall();
        networkNode.getAllConnections().stream()
                .filter(connection -> !connection.hasPeersNodeAddress())
                .forEach(connection -> UserThread.runAfter(() -> {
                    // We give 30 seconds delay and check again if still no address is set
                    if (!connection.hasPeersNodeAddress() && !connection.isStopped()) {
                        log.info("We close the connection as the peer address is still unknown.\n\t" +
                                "connection=" + connection);
                        connection.shutDown(CloseConnectionReason.UNKNOWN_PEER_ADDRESS);
                    }
                }, REMOVE_ANONYMOUS_PEER_SEC));
    }

    private void removeSuperfluousSeedNodes() {
        Log.traceCall();
        if (networkNode.getConfirmedConnections().size() > maxConnections) {
            Set<Connection> connections = networkNode.getConfirmedConnections();
            if (hasSufficientConnections()) {
                List<Connection> candidates = connections.stream()
                        .filter(this::isSeedNode)
                        .collect(Collectors.toList());

                if (candidates.size() > 1) {
                    candidates.sort((o1, o2) -> ((Long) o1.getStatistic().getLastActivityTimestamp()).compareTo(((Long) o2.getStatistic().getLastActivityTimestamp())));
                    log.info("Number of connections exceeding MAX_CONNECTIONS_EXTENDED_1. Current size=" + candidates.size());
                    Connection connection = candidates.remove(0);
                    log.info("We are going to shut down the oldest connection.\n\tconnection=" + connection.toString());
                    connection.shutDown(CloseConnectionReason.TOO_MANY_SEED_NODES_CONNECTED, this::removeSuperfluousSeedNodes);
                }
            }
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Reported peers
    ///////////////////////////////////////////////////////////////////////////////////////////

    private boolean removeReportedPeer(Peer reportedPeer) {
        boolean contained = reportedPeers.remove(reportedPeer);
        printReportedPeers();
        return contained;
    }

    @Nullable
    private Peer removeReportedPeer(NodeAddress nodeAddress) {
        Optional<Peer> reportedPeerOptional = reportedPeers.stream()
                .filter(e -> e.nodeAddress.equals(nodeAddress)).findAny();
        if (reportedPeerOptional.isPresent()) {
            Peer reportedPeer = reportedPeerOptional.get();
            removeReportedPeer(reportedPeer);
            return reportedPeer;
        } else {
            return null;
        }
    }

    private void removeTooOldReportedPeers() {
        Log.traceCall();
        Set<Peer> reportedPeersToRemove = reportedPeers.stream()
                .filter(reportedPeer -> new Date().getTime() - reportedPeer.date.getTime() > MAX_AGE)
                .collect(Collectors.toSet());
        reportedPeersToRemove.forEach(this::removeReportedPeer);
    }

    public Set<Peer> getReportedPeers() {
        return reportedPeers;
    }

    public void addToReportedPeers(HashSet<Peer> reportedPeersToAdd, Connection connection) {
        printNewReportedPeers(reportedPeersToAdd);

        // We check if the reported msg is not violating our rules
        if (reportedPeersToAdd.size() <= (MAX_REPORTED_PEERS + maxConnectionsAbsolute + 10)) {
            reportedPeers.addAll(reportedPeersToAdd);
            purgeReportedPeersIfExceeds();

            persistedPeers.addAll(reportedPeersToAdd);
            purgePersistedPeersIfExceeds();
            if (dbStorage != null)
                dbStorage.queueUpForSave(persistedPeers, 2000);

            printReportedPeers();
        } else {
            // If a node is trying to send too many peers we treat it as rule violation.
            // Reported peers include the connected peers. We use the max value and give some extra headroom.
            // Will trigger a shutdown after 2nd time sending too much
            connection.reportIllegalRequest(RuleViolation.TOO_MANY_REPORTED_PEERS_SENT);
        }
    }

    private void purgeReportedPeersIfExceeds() {
        Log.traceCall();
        int size = reportedPeers.size();
        int limit = MAX_REPORTED_PEERS - maxConnectionsAbsolute;
        if (size > limit) {
            log.trace("We have already {} reported peers which exceeds our limit of {}." +
                    "We remove random peers from the reported peers list.", size, limit);
            int diff = size - limit;
            List<Peer> list = new ArrayList<>(reportedPeers);
            // we dont use sorting by lastActivityDate to keep it more random
            for (int i = 0; i < diff; i++) {
                Peer toRemove = list.remove(new Random().nextInt(list.size()));
                removeReportedPeer(toRemove);
            }
        } else {
            log.trace("No need to purge reported peers.\n\tWe don't have more then {} reported peers yet.", MAX_REPORTED_PEERS);
        }
    }

    private void printReportedPeers() {
        if (!reportedPeers.isEmpty()) {
            if (printReportedPeersDetails) {
                StringBuilder result = new StringBuilder("\n\n------------------------------------------------------------\n" +
                        "Collected reported peers:");
                reportedPeers.stream().forEach(e -> result.append("\n").append(e));
                result.append("\n------------------------------------------------------------\n");
                log.info(result.toString());
            }
            log.info("Number of collected reported peers: {}", reportedPeers.size());
        }
    }

    private void printNewReportedPeers(HashSet<Peer> reportedPeers) {
        if (printReportedPeersDetails) {
            StringBuilder result = new StringBuilder("We received new reportedPeers:");
            reportedPeers.stream().forEach(e -> result.append("\n\t").append(e));
            log.info(result.toString());
        }
        log.info("Number of new arrived reported peers: {}", reportedPeers.size());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    //  Persisted peers
    ///////////////////////////////////////////////////////////////////////////////////////////

    private boolean removePersistedPeer(Peer persistedPeer) {
        if (persistedPeers.contains(persistedPeer)) {
            persistedPeers.remove(persistedPeer);

            if (dbStorage != null)
                dbStorage.queueUpForSave(persistedPeers, 2000);

            return true;
        } else {
            return false;
        }
    }

    private boolean removePersistedPeer(NodeAddress nodeAddress) {
        Optional<Peer> persistedPeerOptional = getPersistedPeerOptional(nodeAddress);
        return persistedPeerOptional.isPresent() && removePersistedPeer(persistedPeerOptional.get());
    }

    private Optional<Peer> getPersistedPeerOptional(NodeAddress nodeAddress) {
        return persistedPeers.stream()
                .filter(e -> e.nodeAddress.equals(nodeAddress)).findAny();
    }

    private void removeTooOldPersistedPeers() {
        Log.traceCall();
        Set<Peer> persistedPeersToRemove = persistedPeers.stream()
                .filter(reportedPeer -> new Date().getTime() - reportedPeer.date.getTime() > MAX_AGE)
                .collect(Collectors.toSet());
        persistedPeersToRemove.forEach(this::removePersistedPeer);
    }

    private void purgePersistedPeersIfExceeds() {
        Log.traceCall();
        int size = persistedPeers.size();
        int limit = MAX_PERSISTED_PEERS;
        if (size > limit) {
            log.trace("We have already {} persisted peers which exceeds our limit of {}." +
                    "We remove random peers from the persisted peers list.", size, limit);
            int diff = size - limit;
            List<Peer> list = new ArrayList<>(persistedPeers);
            // we dont use sorting by lastActivityDate to avoid attack vectors and keep it more random
            for (int i = 0; i < diff; i++) {
                Peer toRemove = list.remove(new Random().nextInt(list.size()));
                removePersistedPeer(toRemove);
            }
        } else {
            log.trace("No need to purge persisted peers.\n\tWe don't have more then {} persisted peers yet.", MAX_PERSISTED_PEERS);
        }
    }

    public Set<Peer> getPersistedPeers() {
        return persistedPeers;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    //  Misc
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean hasSufficientConnections() {
        return networkNode.getNodeAddressesOfConfirmedConnections().size() >= minConnections;
    }

    public boolean isSeedNode(Peer reportedPeer) {
        return seedNodeAddresses.contains(reportedPeer.nodeAddress);
    }

    public boolean isSeedNode(NodeAddress nodeAddress) {
        return seedNodeAddresses.contains(nodeAddress);
    }

    public boolean isSeedNode(Connection connection) {
        return connection.hasPeersNodeAddress() && seedNodeAddresses.contains(connection.getPeersNodeAddressOptional().get());
    }

    public boolean isSelf(Peer reportedPeer) {
        return isSelf(reportedPeer.nodeAddress);
    }

    public boolean isSelf(NodeAddress nodeAddress) {
        return nodeAddress.equals(networkNode.getNodeAddress());
    }

    public boolean isConfirmed(Peer reportedPeer) {
        return isConfirmed(reportedPeer.nodeAddress);
    }

    // Checks if that connection has the peers node address
    public boolean isConfirmed(NodeAddress nodeAddress) {
        return networkNode.getNodeAddressesOfConfirmedConnections().contains(nodeAddress);
    }

    public void handleConnectionFault(Connection connection) {
        connection.getPeersNodeAddressOptional().ifPresent(nodeAddress -> handleConnectionFault(nodeAddress, connection));
    }

    public void handleConnectionFault(NodeAddress nodeAddress) {
        handleConnectionFault(nodeAddress, null);
    }

    public void handleConnectionFault(NodeAddress nodeAddress, @Nullable Connection connection) {
        Log.traceCall("nodeAddress=" + nodeAddress);
        boolean doRemovePersistedPeer = false;
        removeReportedPeer(nodeAddress);
        Optional<Peer> persistedPeerOptional = getPersistedPeerOptional(nodeAddress);
        if (persistedPeerOptional.isPresent()) {
            Peer persistedPeer = persistedPeerOptional.get();
            persistedPeer.increaseFailedConnectionAttempts();
            doRemovePersistedPeer = persistedPeer.tooManyFailedConnectionAttempts();
        }
        doRemovePersistedPeer = doRemovePersistedPeer || (connection != null && connection.getRuleViolation() != null);

        if (doRemovePersistedPeer)
            removePersistedPeer(nodeAddress);
        else
            removeTooOldPersistedPeers();
    }

    public void shutDownConnection(Connection connection, CloseConnectionReason closeConnectionReason) {
        if (connection.getPeerType() != Connection.PeerType.DIRECT_MSG_PEER)
            connection.shutDown(closeConnectionReason);
    }

    public void shutDownConnection(NodeAddress peersNodeAddress, CloseConnectionReason closeConnectionReason) {
        networkNode.getAllConnections().stream()
                .filter(connection -> connection.getPeersNodeAddressOptional().isPresent() &&
                        connection.getPeersNodeAddressOptional().get().equals(peersNodeAddress) &&
                        connection.getPeerType() != Connection.PeerType.DIRECT_MSG_PEER)
                .findAny()
                .ifPresent(connection -> connection.shutDown(closeConnectionReason));
    }

    public HashSet<Peer> getConnectedNonSeedNodeReportedPeers(NodeAddress excludedNodeAddress) {
        return new HashSet<>(getConnectedNonSeedNodeReportedPeers().stream()
                .filter(e -> !e.nodeAddress.equals(excludedNodeAddress))
                .collect(Collectors.toSet()));
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    //  Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private Set<Peer> getConnectedReportedPeers() {
        // networkNode.getConfirmedConnections includes:
        // filter(connection -> connection.getPeersNodeAddressOptional().isPresent())
        return networkNode.getConfirmedConnections().stream()
                .map(c -> new Peer(c.getPeersNodeAddressOptional().get()))
                .collect(Collectors.toSet());
    }

    private HashSet<Peer> getConnectedNonSeedNodeReportedPeers() {
        return new HashSet<>(getConnectedReportedPeers().stream()
                .filter(e -> !isSeedNode(e))
                .collect(Collectors.toSet()));
    }

    private void stopCheckMaxConnectionsTimer() {
        if (checkMaxConnectionsTimer != null) {
            checkMaxConnectionsTimer.stop();
            checkMaxConnectionsTimer = null;
        }
    }

    private void printConnectedPeers() {
        if (!networkNode.getConfirmedConnections().isEmpty()) {
            StringBuilder result = new StringBuilder("\n\n------------------------------------------------------------\n" +
                    "Connected peers for node " + networkNode.getNodeAddress() + ":");
            networkNode.getConfirmedConnections().stream().forEach(e -> result.append("\n")
                    .append(e.getPeersNodeAddressOptional().get()).append(" ").append(e.getPeerType()));
            result.append("\n------------------------------------------------------------\n");
            log.info(result.toString());
        }
    }
}
