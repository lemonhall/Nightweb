/* I2PSOCKSTunnel is released under the terms of the GNU GPL,
 * with an additional exception.  For further details, see the
 * licensing terms in I2PTunnel.java.
 *
 * Copyright (c) 2004 by human
 */
package net.i2p.i2ptunnel.socks;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.DataFormatException;
import net.i2p.util.HexDump;
import net.i2p.util.Log;

/*
 * Class that manages SOCKS 4/4a connections, and forwards them to
 * destination hosts or (eventually) some outproxy.
 *
 * @author zzz modded from SOCKS5Server
 */
public class SOCKS4aServer extends SOCKSServer {
    private static final Log _log = new Log(SOCKS4aServer.class);

    private Socket clientSock = null;
    private boolean setupCompleted = false;

    /**
     * Create a SOCKS4a server that communicates with the client using
     * the specified socket.  This method should not be invoked
     * directly: new SOCKS4aServer objects should be created by using
     * SOCKSServerFactory.createSOCSKServer().  It is assumed that the
     * SOCKS VER field has been stripped from the input stream of the
     * client socket.
     *
     * @param clientSock client socket
     */
    public SOCKS4aServer(Socket clientSock) {
        this.clientSock = clientSock;
    }

    public Socket getClientSocket() throws SOCKSException {
        setupServer();

        return clientSock;
    }

    protected void setupServer() throws SOCKSException {
        if (setupCompleted) { return; }

        DataInputStream in;
        DataOutputStream out;
        try {
            in = new DataInputStream(clientSock.getInputStream());
            out = new DataOutputStream(clientSock.getOutputStream());

            manageRequest(in, out);
        } catch (IOException e) {
            throw new SOCKSException("Connection error (" + e.getMessage() + ")");
        }

        setupCompleted = true;
    }

    /**
     * SOCKS4a request management.  This method assumes that all the
     * stuff preceding or enveloping the actual request
     * has been stripped out of the input/output streams.
     */
    private void manageRequest(DataInputStream in, DataOutputStream out) throws IOException, SOCKSException {

        int command = in.readByte() & 0xff;
        switch (command) {
        case Command.CONNECT:
            break;
        case Command.BIND:
            _log.debug("BIND command is not supported!");
            sendRequestReply(Reply.CONNECTION_REFUSED, InetAddress.getByName("127.0.0.1"), 0, out);
            throw new SOCKSException("BIND command not supported");
        default:
            _log.debug("unknown command in request (" + Integer.toHexString(command) + ")");
            sendRequestReply(Reply.CONNECTION_REFUSED, InetAddress.getByName("127.0.0.1"), 0, out);
            throw new SOCKSException("Invalid command in request");
        }

        connPort = in.readUnsignedShort();
        if (connPort == 0) {
            _log.debug("trying to connect to TCP port 0?  Dropping!");
            sendRequestReply(Reply.CONNECTION_REFUSED, InetAddress.getByName("127.0.0.1"), 0, out);
            throw new SOCKSException("Invalid port number in request");
        }

        connHostName = new String("");
        boolean alreadyWarned = false;
        for (int i = 0; i < 4; ++i) {
            int octet = in.readByte() & 0xff;
            connHostName += Integer.toString(octet);
            if (i != 3) {
                connHostName += ".";
                if (octet != 0 && !alreadyWarned) {
                    _log.warn("IPV4 address type in request: " + connHostName + ". Is your client secure?");
                    alreadyWarned = true;
                }
            }
        }

        // discard user name
        readString(in);

        // SOCKS 4a
        if (connHostName.startsWith("0.0.0.") && !connHostName.equals("0.0.0.0"))
            connHostName = readString(in);
    }

    private String readString(DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(16);
        char c;
        while ((c = (char) (in.readByte() & 0xff)) != 0)
            sb.append(c);
        return sb.toString();
    }

    protected void confirmConnection() throws SOCKSException {
        DataOutputStream out;
        try {
            out = new DataOutputStream(clientSock.getOutputStream());

            sendRequestReply(Reply.SUCCEEDED, InetAddress.getByName("127.0.0.1"), 1, out);
        } catch (IOException e) {
            throw new SOCKSException("Connection error (" + e.getMessage() + ")");
        }
    }

    /**
     * Send the specified reply to a request of the client.  Either
     * one of inetAddr or domainName can be null, depending on
     * addressType.
     */
    private void sendRequestReply(int replyCode, InetAddress inetAddr,
                                  int bindPort, DataOutputStream out) throws IOException {
        ByteArrayOutputStream reps = new ByteArrayOutputStream();
        DataOutputStream dreps = new DataOutputStream(reps);

        // Reserved byte, should be 0x00
        dreps.write(0x00);
        dreps.write(replyCode);
        dreps.writeShort(bindPort);
        dreps.write(inetAddr.getAddress());

        byte[] reply = reps.toByteArray();

        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("Sending request reply:\n" + HexDump.dump(reply));
        }

        out.write(reply);
    }

    /**
     * Get an I2PSocket that can be used to send/receive 8-bit clean data
     * to/from the destination of the SOCKS connection.
     *
     * @return an I2PSocket connected with the destination
     */
    public I2PSocket getDestinationI2PSocket(I2PSOCKSTunnel t) throws SOCKSException {
        setupServer();

        if (connHostName == null) {
            _log.error("BUG: destination host name has not been initialized!");
            throw new SOCKSException("BUG! See the logs!");
        }
        if (connPort == 0) {
            _log.error("BUG: destination port has not been initialized!");
            throw new SOCKSException("BUG! See the logs!");
        }

        DataOutputStream out; // for errors
        try {
            out = new DataOutputStream(clientSock.getOutputStream());
        } catch (IOException e) {
            throw new SOCKSException("Connection error (" + e.getMessage() + ")");
        }

        // FIXME: here we should read our config file, select an
        // outproxy, and instantiate the proper socket class that
        // handles the outproxy itself (SOCKS4a, SOCKS4a, HTTP CONNECT...).
        I2PSocket destSock;

        try {
            if (connHostName.toLowerCase(Locale.US).endsWith(".i2p") ||
                connHostName.toLowerCase(Locale.US).endsWith(".onion")) {
                _log.debug("connecting to " + connHostName + "...");
                // Let's not due a new Dest for every request, huh?
                //I2PSocketManager sm = I2PSocketManagerFactory.createManager();
                //destSock = sm.connect(I2PTunnel.destFromName(connHostName), null);
                Properties overrides = new Properties();
                I2PSocketOptions sktOpts = t.buildOptions(overrides);
                sktOpts.setPort(connPort);
                destSock = t.createI2PSocket(I2PAppContext.getGlobalContext().namingService().lookup(connHostName), sktOpts);
            } else if ("localhost".equals(connHostName) || "127.0.0.1".equals(connHostName)) {
                String err = "No localhost accesses allowed through the Socks Proxy";
                _log.error(err);
                try {
                    sendRequestReply(Reply.CONNECTION_REFUSED, InetAddress.getByName("127.0.0.1"), 0, out);
                } catch (IOException ioe) {}
                throw new SOCKSException(err);
            } else if (connPort == 80) {
                // rewrite GET line to include hostname??? or add Host: line???
                // or forward to local eepProxy (but that's a Socket not an I2PSocket)
                // use eepProxy configured outproxies?
                String err = "No handler for HTTP outproxy implemented - to: " + connHostName;
                _log.error(err);
                try {
                    sendRequestReply(Reply.CONNECTION_REFUSED, InetAddress.getByName("127.0.0.1"), 0, out);
                } catch (IOException ioe) {}
                throw new SOCKSException(err);
            } else {
                List<String> proxies = t.getProxies(connPort);
                if (proxies == null || proxies.isEmpty()) {
                    String err = "No outproxy configured for port " + connPort + " and no default configured either - host: " + connHostName;
                    _log.error(err);
                    try {
                        sendRequestReply(Reply.CONNECTION_REFUSED, InetAddress.getByName("127.0.0.1"), 0, out);
                    } catch (IOException ioe) {}
                    throw new SOCKSException(err);
                }
                int p = I2PAppContext.getGlobalContext().random().nextInt(proxies.size());
                String proxy = proxies.get(p);
                _log.debug("connecting to port " + connPort + " proxy " + proxy + " for " + connHostName + "...");
                // this isn't going to work, these need to be socks outproxies so we need
                // to do a socks session to them?
                destSock = t.createI2PSocket(I2PAppContext.getGlobalContext().namingService().lookup(proxy));
            }
            confirmConnection();
            _log.debug("connection confirmed - exchanging data...");
        } catch (DataFormatException e) {
            try {
                sendRequestReply(Reply.CONNECTION_REFUSED, InetAddress.getByName("127.0.0.1"), 0, out);
            } catch (IOException ioe) {}
            throw new SOCKSException("Error in destination format");
        } catch (SocketException e) {
            try {
                sendRequestReply(Reply.CONNECTION_REFUSED, InetAddress.getByName("127.0.0.1"), 0, out);
            } catch (IOException ioe) {}
            throw new SOCKSException("Error connecting ("
                                     + e.getMessage() + ")");
        } catch (IOException e) {
            try {
                sendRequestReply(Reply.CONNECTION_REFUSED, InetAddress.getByName("127.0.0.1"), 0, out);
            } catch (IOException ioe) {}
            throw new SOCKSException("Error connecting ("
                                     + e.getMessage() + ")");
        } catch (I2PException e) {
            try {
                sendRequestReply(Reply.CONNECTION_REFUSED, InetAddress.getByName("127.0.0.1"), 0, out);
            } catch (IOException ioe) {}
            throw new SOCKSException("Error connecting ("
                                     + e.getMessage() + ")");
        }

        return destSock;
    }

    /*
     * Some namespaces to enclose SOCKS protocol codes
     */
    private static class Command {
        private static final int CONNECT = 0x01;
        private static final int BIND = 0x02;
    }

    private static class Reply {
        private static final int SUCCEEDED = 0x5a;
        private static final int CONNECTION_REFUSED = 0x5b;
    }
}