/*
 * Copyright 2011 Google Inc.
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

package org.bitcoinj.core;

import org.bitcoinj.base.Network;
import org.bitcoinj.base.Sha256Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * <p>A Message is a data structure that can be serialized/deserialized using the Bitcoin serialization format.
 * Specific types of messages that are used both in the block chain, and on the wire, are derived from this
 * class.</p>
 * 
 * <p>Instances of this class are not safe for use by multiple threads.</p>
 */
public abstract class Message {
    private static final Logger log = LoggerFactory.getLogger(Message.class);

    public static final int MAX_SIZE = 0x02000000; // 32MB

    protected final MessageSerializer serializer;

    @Nullable
    protected final Network network;

    protected Message() {
        this.network = null;
        this.serializer = DummySerializer.DEFAULT;
    }

    protected Message(Network network) {
        this.network = network;
        this.serializer = NetworkParameters.of(network).getDefaultSerializer();
    }

    protected Message(MessageSerializer serializer) {
        this.network = null;
        this.serializer = serializer;
    }

    protected Message(Network network, MessageSerializer serializer) {
        this.network = network;
        this.serializer = serializer;
    }

    /**
     * 
     * @param network the network this message is created for
     * @param payload Bitcoin protocol formatted byte array containing message content.
     * @param serializer the serializer to use for this message.
     * @throws ProtocolException
     */
    protected Message(Network network, ByteBuffer payload, MessageSerializer serializer) throws ProtocolException {
        this.serializer = serializer;
        this.network = network;

        try {
            parse(payload);
        } catch(BufferUnderflowException e) {
            throw new ProtocolException(e);
        }
    }

    protected Message(ByteBuffer payload) throws ProtocolException {
        this(null, payload, DummySerializer.DEFAULT);
    }

    protected Message(ByteBuffer payload, MessageSerializer serializer) throws ProtocolException {
        this(null, payload, serializer);
    }

    protected Message(Network network, ByteBuffer payload) throws ProtocolException {
        this(network, payload, NetworkParameters.of(network).getDefaultSerializer());
    }

    // These methods handle the serialization/deserialization using the custom Bitcoin protocol.

    protected abstract void parse(ByteBuffer payload) throws BufferUnderflowException, ProtocolException;

    /**
     * <p>Serialize this message to a byte array that conforms to the bitcoin wire protocol.</p>
     *
     * @return a byte array
     */
    public final byte[] bitcoinSerialize() {
        // No cached array available so serialize parts by stream.
        ByteArrayOutputStream stream = new ByteArrayOutputStream(100); // initial size just a guess
        try {
            bitcoinSerializeToStream(stream);
        } catch (IOException e) {
            // Cannot happen, we are serializing to a memory stream.
        }
        return stream.toByteArray();
    }

    /** @deprecated use {@link #bitcoinSerialize()} */
    @Deprecated
    public byte[] unsafeBitcoinSerialize() {
        return bitcoinSerialize();
    }

    /**
     * Serializes this message to the provided stream. If you just want the raw bytes use bitcoinSerialize().
     */
    protected void bitcoinSerializeToStream(OutputStream stream) throws IOException {
        log.error("Error: {} class has not implemented bitcoinSerializeToStream method.  Generating message with no payload", getClass());
    }

    /** @deprecated use {@link Transaction#getTxId()}, {@link Block#getHash()}, {@link FilteredBlock#getHash()} or {@link TransactionOutPoint#getHash()} */
    @Deprecated
    public Sha256Hash getHash() {
        throw new UnsupportedOperationException();
    }

    /**
     * Return the size of the serialized message. Note that if the message was deserialized from a payload, this
     * size can differ from the size of the original payload.
     * @return size of the serialized message in bytes
     */
    public int getMessageSize() {
        return bitcoinSerialize().length;
    }

    /**
     * Gets the network this message was created for.
     *
     * @return network this message was created for
     */
    public Network network() {
        return Objects.requireNonNull(network);
    }

    /** Network parameters this message was created with. */
    public NetworkParameters getParams() {
        return NetworkParameters.of(network);
    }
}
