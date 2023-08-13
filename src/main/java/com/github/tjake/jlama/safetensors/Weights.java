package com.github.tjake.jlama.safetensors;

import com.github.tjake.jlama.math.FloatConversions;
import com.github.tjake.jlama.model.FloatBufferTensor;
import com.google.common.primitives.Ints;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

public class Weights implements WeightLoader {

    private final Map<String, String> metadata;
    private final Map<String, TensorInfo> tensorInfoMap;
    private final ByteBuffer bytes;

    Weights(Map<String, String> metadata, Map<String, TensorInfo> tensorInfoMap, ByteBuffer bytes)
    {
        this.metadata = metadata;
        this.tensorInfoMap = tensorInfoMap;
        this.bytes = bytes.duplicate();
    }

    @Override
    public FloatBufferTensor load(String name) throws NoSuchElementException {
        TensorInfo info = tensorInfoMap.get(name);
        if (info == null)
            throw new NoSuchElementException();

        if (info.shape.length < 1)
            throw new RuntimeException("Invalid shape dimensions " + info.shape.length + " encountered for " + name);

        ByteBuffer b = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                .position(Ints.checkedCast(info.dataOffsets[0]))
                .limit(Ints.checkedCast(info.dataOffsets[1]));

        FloatBuffer fb;
        switch (info.dType) {
            case F32:
                int len = b.remaining() / DType.F32.size();
                fb = FloatBuffer.allocate(len);
                for (int i = 0; i < len; i++) {
                    float v = b.getFloat();
                    fb.put(i, v);
                }
                //fb = b.asFloatBuffer().slice();
                break;
            case F16:
                 len = b.remaining() / DType.F16.size();
                fb = FloatBuffer.allocate(len);
                for (int i = 0; i < len; i++) {
                    short s = b.getShort();
                    float v = Float.float16ToFloat(s);
                    fb.put(i, v);
                }
                break;
            case BF16:
                len = b.remaining() / DType.F16.size();
                fb = FloatBuffer.allocate(len);
                for (int i = 0; i < len; i++) {
                    short s = b.getShort();
                    float v = FloatConversions.bFloat16ToFloat32(s);
                    fb.put(i, v);
                }
                break;
            default:
                throw new IllegalArgumentException("Unsupported Tensor type: " + info.dType.name() + " for " + name);
        }

        return new FloatBufferTensor(fb, info.shape, true, true);
    }

    @Override
    public String toString() {
        return "SafeTensor{" +
                "metadata=" + metadata +
                ", tensorInfoMap=" + tensorInfoMap +
                ", bytes=" + bytes +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Weights weights = (Weights) o;
        return Objects.equals(metadata, weights.metadata) && Objects.equals(tensorInfoMap, weights.tensorInfoMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(metadata, tensorInfoMap);
    }

}
