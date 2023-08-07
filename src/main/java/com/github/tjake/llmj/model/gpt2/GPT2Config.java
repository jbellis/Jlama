package com.github.tjake.llmj.model.gpt2;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.tjake.llmj.safetensors.Config;

public class GPT2Config extends Config {

    @JsonCreator
    public GPT2Config( @JsonProperty("n_ctx") int contextLength,
                       @JsonProperty("n_embd") int embeddingLength,
                       @JsonProperty("n_head") int numberOfHeads,
                       @JsonProperty("n_layer") int numberOfLayers,
                       @JsonProperty("layer_norm_epsilon") float layerNormEps,
                       @JsonProperty("vocab_size") int vocabularySize,
                       @JsonProperty("bos_token_id") int bosToken,
                       @JsonProperty("eos_token_id") int eosToken) {
        super(contextLength, embeddingLength, embeddingLength * 4, numberOfHeads, numberOfLayers, layerNormEps, vocabularySize, bosToken, eosToken);
    }
}
