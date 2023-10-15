package com.github.tjake.jlama.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tjake.jlama.math.VectorMath;
import com.github.tjake.jlama.safetensors.Config;
import com.github.tjake.jlama.safetensors.DType;
import com.github.tjake.jlama.safetensors.SafeTensorSupport;
import com.github.tjake.jlama.safetensors.Tokenizer;
import com.github.tjake.jlama.safetensors.WeightLoader;
import com.github.tjake.jlama.tensor.AbstractTensor;
import com.github.tjake.jlama.tensor.Q8ByteBufferTensor;
import com.github.tjake.jlama.tensor.TensorCache;
import com.github.tjake.jlama.tensor.operations.TensorOperationsProvider;

import com.github.tjake.jlama.util.PhysicalCoreExecutor;
import com.google.common.base.Preconditions;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.AtomicDouble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public abstract class AbstractModel {
    private static final Logger logger = LoggerFactory.getLogger(AbstractModel.class);
    private static final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true);
    protected final Config c;
    protected final WeightLoader weights;
    protected final Tokenizer tokenizer;
    protected final DType modelDType;
    protected final DType workingDType;
    protected final DType workingQType;
    private static final ThreadLocal<AbstractTensor[]> tmpArray = new ThreadLocal<>();

    protected AbstractModel(Config c, WeightLoader w, Tokenizer t, DType workingMemoryDType, DType workingMemoryQType)
    {
        this.c = c;
        this.weights = w;
        this.tokenizer = t;
        this.modelDType = w.getModelDType();
        this.workingDType = workingMemoryDType;

        if (workingMemoryQType != workingMemoryDType) {
            boolean supportsQType;
            AbstractTensor tmp = makeTensor(Q8ByteBufferTensor.BLOCK_SIZE);
            try (AbstractTensor tmp2 = TensorOperationsProvider.get().quantize(tmp, workingMemoryQType)) {
                supportsQType = tmp2.dType() == workingMemoryQType;
                if (!supportsQType) {
                    logger.warn("Quantized memory type {} not supported, falling back to {}", workingMemoryQType, workingMemoryDType);
                    this.workingQType = this.workingDType;
                } else {
                    this.workingQType = workingMemoryQType;
                }
            }
        } else {
            this.workingQType = workingMemoryQType;
        }

        logger.info("Working memory type = {}, Quantized memory type = {}", this.workingDType, this.workingQType);
    }

    public static AbstractModel load(File model, int threadCount, DType workingMemoryType, DType workingQuantizationType) throws FileNotFoundException {
        if (!model.exists()) {
            throw new FileNotFoundException("Model does not exist: " + model);
        }

        File baseDir = model.isFile() ? model.getParentFile() : model;
        assert baseDir.isDirectory() : "Neither model location nor its parent is a directory!? " + model;

        File configFile = null;
        for (File f : Objects.requireNonNull(baseDir.listFiles())) {
            if (f.getName().equals("config.json")) {
                configFile = f;
                break;
            }
        }

        if (configFile == null) {
            throw new FileNotFoundException("config.json does not exist in model directory " + baseDir);
        }

        try {
            PhysicalCoreExecutor.overrideThreadCount(threadCount);

            ModelSupport.ModelType modelType = SafeTensorSupport.detectModel(configFile);

            Config c = om.readValue(configFile, modelType.configClass);
            Tokenizer t = modelType.tokenizerClass.getConstructor(Path.class).newInstance(baseDir.toPath());
            WeightLoader wl = SafeTensorSupport.loadWeights(baseDir);

            return modelType.modelClass.getConstructor(Config.class, WeightLoader.class, Tokenizer.class, DType.class, DType.class)
                    .newInstance(c, wl, t, workingMemoryType, workingQuantizationType);

        } catch (IOException | NoSuchMethodException | InvocationTargetException | InstantiationException |
                 IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public String wrapPrompt(String prompt, Optional<String> systemPrompt) {
        return prompt;
    }

    protected abstract AbstractTensor inputTokenToEmbedding(int inputToken, int position);

    protected abstract TransformerBlock[] getTransformerBlocks();

    protected abstract LayerNorm getOutputLayerNorm();

    protected abstract AbstractTensor getOutputLogitsWeights();

    protected AbstractTensor makeTensor(int ...shape) {
        return c.tensorCache.get(workingDType, shape);
    }

    protected AbstractTensor maybeQuantize(AbstractTensor t) {
        AbstractTensor t2 = TensorCache.instance.get(t.dType(), t.shape());
        t2.copyFrom(t, 0, 0, t.size());
        return t2;
    }

    protected AbstractTensor forward(int token_id, int pos, AbstractTensor kvbuf) {
        AbstractTensor embedding = inputTokenToEmbedding(token_id, pos);
        TransformerBlock[] transformerBlocks = getTransformerBlocks();

        for (int i = 0; i < c.numberOfLayers; i++) {
            AbstractTensor kvlayer = kvbuf.slice(i);
            AbstractTensor ref = embedding; //reference so we can free
            embedding = transformerBlocks[i].forward(embedding, pos, kvlayer);
            ref.close();
        }
        return embedding;
    }

    protected AbstractTensor[] batchForward(int[] token_ids, int startPos, AbstractTensor kvbuf) {
        TransformerBlock[] transformerBlocks = getTransformerBlocks();
        int batchSize = token_ids.length;

        AbstractTensor[] embeddings = tmpArray.get();
        if (embeddings == null || embeddings.length < batchSize) {
            embeddings = new AbstractTensor[batchSize];
            tmpArray.set(embeddings);
        }
        AbstractTensor[] emf = embeddings;
        VectorMath.pfor(0, batchSize, i -> {
            emf[i] = inputTokenToEmbedding(token_ids[i], startPos+i);
        });

        for (int i = 0; i < c.numberOfLayers; i++) {
            AbstractTensor kvlayer = kvbuf.slice(i);
            for (int j = 0; j < batchSize; j++) {
                AbstractTensor ref = embeddings[j];
                embeddings[j] = transformerBlocks[i].forward(ref, startPos + j, kvlayer);
                ref.close();
            }
        }

        return embeddings;
    }

    protected int sample(AbstractTensor output, float temperature, float uniformSample, AbstractTensor logits) {
        try(AbstractTensor embedding = getOutputLayerNorm().forward(output)) {

            AtomicDouble maxv = new AtomicDouble(Double.NEGATIVE_INFINITY);
            AtomicInteger maxi = new AtomicInteger(Integer.MIN_VALUE);

            //This is a mix of argmax and sampling with softmax
            VectorMath.pfor(0, c.vocabularySize, i -> {
                float v = TensorOperationsProvider.get().dotProduct(embedding, getOutputLogitsWeights().slice(i), c.embeddingLength);
                logits.set(v, i);
                maxv.getAndUpdate(x -> {
                    if (v > x) {
                        maxi.set(i);
                        return v;
                    }
                    return x;
                });
            });

            if (temperature == 0.0) {
                return maxi.get();
            }

            float sum = 0;
            for (int i = 0; i < c.vocabularySize; i++) {
                float v = (float) Math.exp((logits.get(i) - maxv.get()) / temperature);
                sum += v;
                logits.set(v, i);
            }

            float acc = 0;
            for (int i = 0; i < c.vocabularySize; i++) {
                float v = logits.get(i) / sum;
                acc += v;
                if (acc >= uniformSample)
                    return i;
            }

            return c.vocabularySize - 1;
        }
    }

    public void generate(String prompt, float temperature, int ntokens, boolean useEOS, BiConsumer<String, Float> onTokenWithTimings) {
        generate(prompt, null, temperature, ntokens, useEOS, onTokenWithTimings);
    }

    public void generate(String prompt, String cleanPrompt, float temperature, int ntokens, boolean useEOS, BiConsumer<String, Float> onTokenWithTimings) {
        long[] encoded = tokenizer.encode(prompt);
        Preconditions.checkArgument(encoded.length < c.contextLength);

        if (ntokens > c.contextLength)
            ntokens = c.contextLength;

        AbstractTensor kvmem = makeTensor(c.numberOfLayers, ntokens, c.embeddingLength * 2); //k and v are concatenated
        AbstractTensor logits = makeTensor(c.vocabularySize);

        int[] promptTokens = new int[useEOS ? (1 + encoded.length + 1) : (1 + encoded.length)];

        promptTokens[0] = c.bosToken;
        for (int i = 1; i < encoded.length; i++)
            promptTokens[i] = Ints.checkedCast(encoded[i]);

        int promptLength = encoded.length;

        if (useEOS) {
            promptTokens[promptTokens.length - 1] = c.eosToken; //Add EOS
            promptLength++;
        }

        String clientPrompt = cleanPrompt == null ? prompt : cleanPrompt;
        onTokenWithTimings.accept(clientPrompt, 0f);
        long start = System.currentTimeMillis();
        //Batch Process Prompt
        AbstractTensor batch[] = batchForward(promptTokens, 0, kvmem);

        long promptBatchTime = System.currentTimeMillis() - start;
        logger.debug("{} prompt tokens in {}ms {} tokens/sec", promptLength, promptBatchTime, Math.round((((double)promptBatchTime)/(double)promptLength)));

        int tokensGenerated = 0;
        AbstractTensor last = batch[batch.length - 1];

        int next = sample(last, temperature, ThreadLocalRandom.current().nextFloat(), logits);
        try {
            String c = tokenizer.decode(next);
            onTokenWithTimings.accept(c, (System.currentTimeMillis() - start) / (float) (0 + 1));
        } catch (Exception e) {
            logger.error("Failed to decode token {}", next, e);
        }
        start = System.currentTimeMillis();
        for (int i = promptTokens.length - 1; i < ntokens; i++)
        {
            AbstractTensor output = forward(next, i, kvmem);
            tokensGenerated++;
            next = sample(output, temperature, ThreadLocalRandom.current().nextFloat(), logits);

            if (logger.isTraceEnabled())
                logger.trace("Sampled token {} with temperature {}", next, temperature);

            //Model may tell us it's done
            if (next == c.eosToken)
                break;

            try {
                String c = tokenizer.decode(next);
                onTokenWithTimings.accept(c, (System.currentTimeMillis() - start) / (float) (i + 1));
            } catch (Exception e) {
                logger.error("Failed to decode token {}", next, e);
            }
        }

        long end = System.currentTimeMillis();
        System.out.printf("\n\nelapsed: %ds, %fms per token\n", TimeUnit.MILLISECONDS.toSeconds(end - start), ((end - start) / (float)tokensGenerated));
    }
}
