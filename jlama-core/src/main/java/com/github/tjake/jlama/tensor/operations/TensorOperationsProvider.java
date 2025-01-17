package com.github.tjake.jlama.tensor.operations;

import com.github.tjake.jlama.util.MachineSpec;
import com.github.tjake.jlama.util.RuntimeSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TensorOperationsProvider {
    static {
        System.setProperty("jdk.incubator.vector.VECTOR_ACCESS_OOB_CHECK", "0");
    }

    private static final Logger logger = LoggerFactory.getLogger(TensorOperationsProvider.class);

    private static final String lock = "lock";
    private static TensorOperationsProvider instance;
    public static TensorOperations get() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null)
                    instance = new TensorOperationsProvider();
            }
        }

        return instance.provider;
    }

    private final TensorOperations provider;
    private TensorOperationsProvider() {
        this.provider = pickFastestImplementaion();
    }

    private TensorOperations pickFastestImplementaion() {

        TensorOperations pick = null;

        if (RuntimeSupport.isLinux()) {
            try {
                Class<? extends TensorOperations> nativeClazz = (Class<? extends TensorOperations>) Class.forName("com.github.tjake.jlama.tensor.operations.NativeTensorOperations");
                pick = nativeClazz.getConstructor().newInstance();
                //This should break of no shared lib found
            } catch (Throwable t) {
                logger.warn("Error loading native operations", t);
            }
        }

        if (pick == null)
            pick = MachineSpec.VECTOR_TYPE == MachineSpec.Type.NONE ? new NaiveTensorOperations() : new PanamaTensorOperations(MachineSpec.VECTOR_TYPE);

        logger.info("Using {}", pick.name());
        return pick;
    }
}
