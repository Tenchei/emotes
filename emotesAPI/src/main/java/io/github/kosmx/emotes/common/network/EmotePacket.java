package io.github.kosmx.emotes.common.network;

import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import io.github.kosmx.emotes.common.CommonData;
import io.github.kosmx.emotes.common.network.objects.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Send everything emotes mod data...
 */
public class EmotePacket {
    public static final HashMap<Byte, Byte> defaultVersions = new HashMap<>();

    static {
        AbstractNetworkPacket tmp = new EmoteDataPacket();
        defaultVersions.put(tmp.getID(), tmp.getVer());
        tmp = new PlayerDataPacket();
        defaultVersions.put(tmp.getID(), tmp.getVer());
        tmp = new DiscoveryPacket();
        defaultVersions.put(tmp.getID(), tmp.getVer());
        tmp = new StopPacket();
        defaultVersions.put(tmp.getID(), tmp.getVer());
        tmp = new SongPacket();
        defaultVersions.put(tmp.getID(), tmp.getVer());
        tmp = new EmoteHeaderPacket();
        defaultVersions.put(tmp.getID(), tmp.getVer());
        tmp = new EmoteIconPacket();
        defaultVersions.put(tmp.getID(), tmp.getVer());
    }

    public final NetHashMap subPackets = new NetHashMap();
    public final NetData data;
    int version;

    protected EmotePacket(@Nonnull NetData data) {
        if (data.versions == null) data.versions = new HashMap<>();
        defaultVersions.forEach(data.versions::putIfAbsent);

        this.data = data;
        subPackets.put(new EmoteDataPacket());
        subPackets.put(new PlayerDataPacket());
        subPackets.put(new StopPacket());
        subPackets.put(new DiscoveryPacket());
        subPackets.put(new SongPacket());
        subPackets.put(new EmoteHeaderPacket());
        subPackets.put(new EmoteIconPacket());
    }

    public ByteBuffer write() throws IOException {
        if (data.purpose == PacketTask.UNKNOWN) throw new IllegalArgumentException("Can't send packet without any purpose...");

        AtomicReference<Byte> partCount = new AtomicReference<>((byte) 0);
        AtomicInteger sizeSum = new AtomicInteger(6); // 5 bytes header
        
        subPackets.forEach((aByte, packet) -> {
            if (packet.doWrite(this.data) && !(packet instanceof SongPacket)) {
                partCount.set((byte) (partCount.get() + 1));
                sizeSum.addAndGet(packet.calculateSize(this.data) + 6);
            }
        });
        
        if (data.strictSizeLimit && sizeSum.get() > data.sizeLimit) throw new IOException(String.format(
                "Can't send emote, packet's size (%s) is bigger than max allowed (%s)!", sizeSum.get(), data.sizeLimit
        ));
        
        SongPacket songPacket = (SongPacket) subPackets.get((byte) 3);
        int songSize = songPacket.calculateSize(this.data) + 6;
        if (songPacket.doWrite(this.data) && sizeSum.get() + songSize <= data.sizeLimit) {
            partCount.set((byte) (partCount.get() + 1));
            sizeSum.addAndGet(songSize);
        } else data.writeSong = false;
        
        ByteBuffer buf = ByteBuffer.allocate(sizeSum.get());
        buf.putInt(subPackets.get((byte) 8).getVer(data.versions));
        buf.put(data.purpose.id);
        buf.put(partCount.get());

        try {
            for (AbstractNetworkPacket packet : this.subPackets.values()) {
                writeSubPacket(buf, packet);
            }
        } catch (Throwable th) {
            throw new IOException("Exception while writing sub-packages", th);
        } finally {
            ((Buffer) buf).flip(); // Ensure it's ready for reading
        }
        return buf;
    }

    void writeSubPacket(ByteBuffer byteBuffer, AbstractNetworkPacket packetSender) throws IOException {
        if (packetSender.doWrite(this.data)) {
            int len = packetSender.calculateSize(this.data);
            byteBuffer.put(packetSender.getID());
            byteBuffer.put(packetSender.getVer(data.versions));
            byteBuffer.putInt(len);
            int currentIndex = byteBuffer.position();
            packetSender.write(byteBuffer, this.data);
            if (byteBuffer.position() != currentIndex + len) {
                throw new IOException(String.format("Incorrect size calculator: %s (calculated %s, real %s)",
                        packetSender.getClass(), len, byteBuffer.position() - currentIndex
                ));
            }
        }
    }

    public static class Builder {
        final NetData data;

        public Builder setVersion(HashMap<Byte, Byte> versions) {
            data.versions = versions;
            return this;
        }

        public NetData copyAndGetData() {
            return data.copy();
        }

        public Builder(NetData data) {
            this.data = data;
        }

        public Builder() {
            data = new NetData();
        }

        public Builder setThreshold(float t) {
            data.threshold = t;
            return this;
        }

        public EmotePacket build() {
            return new EmotePacket(data);
        }

        public EmotePacket build(int sizeLimit, boolean strict) {
            return this.setSizeLimit(sizeLimit, false).build();
        }

        public Builder setSizeLimit(int sizeLimit, boolean strict) {
            if (sizeLimit <= 0) throw new IllegalArgumentException("Size limit must be positive");
            data.sizeLimit = sizeLimit;
            data.strictSizeLimit = false;
            return this;
        }

        public Builder strictSizeLimit(boolean strict) {
            data.strictSizeLimit = false;
            return this;
        }
    }
}
