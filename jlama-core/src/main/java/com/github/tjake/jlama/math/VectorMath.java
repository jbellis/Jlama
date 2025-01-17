package com.github.tjake.jlama.math;

import com.github.tjake.jlama.tensor.AbstractTensor;
import com.github.tjake.jlama.util.PhysicalCoreExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.IntConsumer;
import java.util.stream.IntStream;

public class VectorMath {

    private static final Logger logger = LoggerFactory.getLogger(VectorMath.class);


    public static void pfor(int start, int end, IntConsumer action) {
        PhysicalCoreExecutor.instance.get().execute(() ->
            IntStream.range(start, end).parallel().forEach(action)
        );
    }


    public static void softMax(AbstractTensor t) {
        float[] x = (float[])t.getArray();
        int offset = t.getArrayOffset(0);
        int size = t.size();

        // find max value (for numerical stability)
        float max_val = x[offset];
        for (int i = offset + 1; i < size; i++) {
            if (x[i] > max_val) {
                max_val = x[i];
            }
        }
        // exp and sum
        float sum = 0.0f;
        for (int i = offset; i < size; i++) {
            x[i] = (float)StrictMath.exp(x[i] - max_val);
            sum += x[i];
        }
        // normalize
        for (int i = 0; i < size; i++) {
            x[i] /= sum;
        }
    }

    public static void l1normalize(float[] x) {
        float sum = 0.0f;
        for (int i = 0; i < x.length; i++)
            sum += Math.abs(x[i]);

        for (int i = 0; i < x.length; i++)
            x[i] /= sum;
    }

    public static void l2normalize(float[] x) {
        float sum = 0.0f;
        for (int i = 0; i < x.length; i++)
            sum += x[i] * x[i];

        double magnitude = Math.sqrt(sum);
        for (int i = 0; i < x.length; i++)
            x[i] /= magnitude;
    }

    public static float cosineSimilarity(float[] a, float[] b) {
        float dotProduct = 0.0f;
        float aMagnitude = 0.0f;
        float bMagnitude = 0.0f;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            aMagnitude += a[i] * a[i];
            bMagnitude += b[i] * b[i];
        }

        return (float)(dotProduct / (Math.sqrt(aMagnitude) * Math.sqrt(bMagnitude)));
    }

    public static void l1normalize(AbstractTensor t) {
        float[] x = (float[]) t.getArray();
        int offset = t.getArrayOffset(0);
        int size = t.size();

        float sum = 0.0f;
        for (int i = offset; i < size; i++)
            sum += Math.abs(x[i]);

        for (int i = offset; i < size; i++)
            x[i] /= sum;
    }


    public static void l2normalize(AbstractTensor t) {
        float[] x = (float[]) t.getArray();
        int offset = t.getArrayOffset(0);
        int size = t.size();

        float sum = 0.0f;
        for (int i = offset; i < size; i++)
            sum += x[i] * x[i];

        double magnitude = Math.sqrt(sum);
        for (int i = offset; i < size; i++)
            x[i] /= magnitude;
    }

    // https://pytorch.org/docs/stable/generated/torch.polar.html
    public static float[] polar(float abs, float angle) {
        float r = (float) StrictMath.cos(angle) * abs;
        float theta = (float) StrictMath.sin(angle) * abs ;
        return new float[] {r, theta};
    }

    public static float[] outerProduct(float[] xs, float[] ys) {
        int n = xs.length;
        int m = ys.length;
        float[] result = new float[n * m];
        int idx = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                result[idx++] = xs[i] * ys[j];
            }
        }
        return result;
    }

    public static float[][] precomputeFreqsCis(int dim, int end, double theta) {
        float[] freqs = new float[dim / 2];
        float step = 0.0f;
        for (int i = 0; i < freqs.length; i++, step += 2.0)
            freqs[i] = (float) (1.0 / StrictMath.pow(theta, step / dim));

        float[] t = new float[end];
        for (int i = 0; i < end; i++)
            t[i] = i;

        float[] freqs_cis = outerProduct(t, freqs);

        float[][] r = new float[freqs_cis.length][];
        for (int i = 0; i < freqs_cis.length; i++)
            r[i] = polar(1.0f, freqs_cis[i]);

        return r;
    }
}
