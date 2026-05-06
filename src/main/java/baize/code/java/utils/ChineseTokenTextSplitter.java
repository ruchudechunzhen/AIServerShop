package baize.code.java.utils;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChineseTokenTextSplitter extends TextSplitter {

    private static final int DEFAULT_CHUNK_SIZE = 800;

    private static final int MIN_CHUNK_SIZE_CHARS = 350;

    private static final int MIN_CHUNK_LENGTH_TO_EMBED = 5;

    private static final int MAX_NUM_CHUNKS = 10000;

    private static final boolean KEEP_SEPARATOR = true;

    private final EncodingRegistry registry = Encodings.newLazyEncodingRegistry();

    private final Encoding encoding = this.registry.getEncoding(EncodingType.CL100K_BASE);

    // The target size of each text chunk in tokens
    private final int chunkSize;

    // The minimum size of each text chunk in characters
    private final int minChunkSizeChars;

    // Discard chunks shorter than this
    private final int minChunkLengthToEmbed;

    // The maximum number of chunks to generate from a text
    private final int maxNumChunks;

    private final boolean keepSeparator;

    public static ChineseTokenTextSplitter quicklyBuilder(){
        return ChineseTokenTextSplitter.builder()
                .withChunkSize(512)
                .withKeepSeparator(false)
                .build();
    }

    public ChineseTokenTextSplitter() {
        this(DEFAULT_CHUNK_SIZE, MIN_CHUNK_SIZE_CHARS, MIN_CHUNK_LENGTH_TO_EMBED, MAX_NUM_CHUNKS, KEEP_SEPARATOR);
    }

    public ChineseTokenTextSplitter(boolean keepSeparator) {
        this(DEFAULT_CHUNK_SIZE, MIN_CHUNK_SIZE_CHARS, MIN_CHUNK_LENGTH_TO_EMBED, MAX_NUM_CHUNKS, keepSeparator);
    }

    public ChineseTokenTextSplitter(int chunkSize, int minChunkSizeChars, int minChunkLengthToEmbed, int maxNumChunks,
                             boolean keepSeparator) {
        this.chunkSize = chunkSize;
        this.minChunkSizeChars = minChunkSizeChars;
        this.minChunkLengthToEmbed = minChunkLengthToEmbed;
        this.maxNumChunks = maxNumChunks;
        this.keepSeparator = keepSeparator;
    }

    public static ChineseTokenTextSplitter.Builder builder() {
        return new ChineseTokenTextSplitter.Builder();
    }

    @Override
    protected List<String> splitText(String text) {
        return doSplit(text, this.chunkSize);
    }

    protected List<String> doSplit(String text, int chunkSize) {
        if (text == null || text.trim().isEmpty()) {
            return new ArrayList<>();
        }

        List<Integer> tokens = getEncodedTokens(text);
        List<String> chunks = new ArrayList<>();
        int num_chunks = 0;
        //循环处理token，直到处理完所有token或达到最大块数限制
        while (!tokens.isEmpty() && num_chunks < this.maxNumChunks) {
            // 获取当前块的token子列表，大小不超过chunkSize
            List<Integer> chunk = tokens.subList(0, Math.min(chunkSize, tokens.size()));
            // 将当前块的token解码回文本
            String chunkText = decodeTokens(chunk);

            // Skip the chunk if it is empty or whitespace
            // 如果解码后的文本为空，则跳过并继续处理下一个块
            if (chunkText.trim().isEmpty()) {
                tokens = tokens.subList(chunk.size(), tokens.size());
                continue;
            }

            // Find the last period or punctuation mark in the chunk
            // 这里只做了针对英文的标点符号处理，要重新写这段逻辑,适配中文
//            int lastPunctuation = Math.max(chunkText.lastIndexOf('.'), Math.max(chunkText.lastIndexOf('?'),
//                    Math.max(chunkText.lastIndexOf('!'), chunkText.lastIndexOf('\n'))));
            // 查找块中的最后一个标点符号，以便在自然边界处分割[尽可能在不超最大字符的同时保留语义]
            // TODO: 查找英文和中文的常见标点符号
            int lastPunctuation =
                    Math.max(chunkText.lastIndexOf("."),
                            Math.max(chunkText.lastIndexOf("?"),
                                    Math.max(chunkText.lastIndexOf("!"),
                                            Math.max(chunkText.lastIndexOf("\n"),
                                                    Math.max(chunkText.lastIndexOf("。"),
                                                            Math.max(chunkText.lastIndexOf("！"),
                                                                    chunkText.lastIndexOf("？")
                                                            )
                                                    )
                                            )
                                    )
                            )
                    );

            // 如果找到了标点符号且位置超过最小块字符数要求，
            // 则在该标点处截断文本以获得更自然的文本边界
            if (lastPunctuation != -1 && lastPunctuation > this.minChunkSizeChars) {
                // Truncate the chunk text at the punctuation mark
                chunkText = chunkText.substring(0, lastPunctuation + 1);
            }

            // 根据keepSeparator设置处理文本
            // 如果keepSeparator为true，则仅去除首尾空格
            // 否则，将换行符替换为空格并去除首尾空格
            // TODO: 需要同时替换\r\n和\n两种换行符，当前只处理了\n
            // 这里调整直接修改连续的空白字符
            String chunkTextToAppend = this.keepSeparator ? chunkText.trim() : chunkText.replaceAll("\\s+", " ").trim();
//            String chunkTextToAppend = (this.keepSeparator) ? chunkText.trim()
//                    : chunkText.replace(System.lineSeparator(), " ").trim();

            // 只有当文本长度满足最小嵌入长度要求时才添加到结果中
            if (chunkTextToAppend.length() > this.minChunkLengthToEmbed) {
                chunks.add(chunkTextToAppend);
            }

            // Remove the tokens corresponding to the chunk text from the remaining tokens
            // 更新token列表，移除已处理的部分
            tokens = tokens.subList(getEncodedTokens(chunkText).size(), tokens.size());

            num_chunks++;
        }

        // Handle the remaining tokens
        if (!tokens.isEmpty()) {
            String remaining_text = decodeTokens(tokens).replace(System.lineSeparator(), " ").trim();
            if (remaining_text.length() > this.minChunkLengthToEmbed) {
                chunks.add(remaining_text);
            }
        }

        return chunks;
    }

    private List<Integer> getEncodedTokens(String text) {
        Assert.notNull(text, "Text must not be null");
        return this.encoding.encode(text).boxed();
    }

    private String decodeTokens(List<Integer> tokens) {
        Assert.notNull(tokens, "Tokens must not be null");
        var tokensIntArray = new IntArrayList(tokens.size());
        tokens.forEach(tokensIntArray::add);
        return this.encoding.decode(tokensIntArray);
    }

    public static final class Builder {

        private int chunkSize = DEFAULT_CHUNK_SIZE;

        private int minChunkSizeChars = MIN_CHUNK_SIZE_CHARS;

        private int minChunkLengthToEmbed = MIN_CHUNK_LENGTH_TO_EMBED;

        private int maxNumChunks = MAX_NUM_CHUNKS;

        private boolean keepSeparator = KEEP_SEPARATOR;

        private Builder() {
        }

        public ChineseTokenTextSplitter.Builder withChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
            return this;
        }

        public ChineseTokenTextSplitter.Builder withMinChunkSizeChars(int minChunkSizeChars) {
            this.minChunkSizeChars = minChunkSizeChars;
            return this;
        }

        public ChineseTokenTextSplitter.Builder withMinChunkLengthToEmbed(int minChunkLengthToEmbed) {
            this.minChunkLengthToEmbed = minChunkLengthToEmbed;
            return this;
        }

        public ChineseTokenTextSplitter.Builder withMaxNumChunks(int maxNumChunks) {
            this.maxNumChunks = maxNumChunks;
            return this;
        }

        public ChineseTokenTextSplitter.Builder withKeepSeparator(boolean keepSeparator) {
            this.keepSeparator = keepSeparator;
            return this;
        }

        public ChineseTokenTextSplitter build() {
            return new ChineseTokenTextSplitter(this.chunkSize, this.minChunkSizeChars, this.minChunkLengthToEmbed,
                    this.maxNumChunks, this.keepSeparator);
        }

    }

}
