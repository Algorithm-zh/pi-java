package com.piagent.domain.context;

import java.util.Objects;

/**
 * 轻量 token 估算器。
 *
 * <p>规则刻意保持简单、可解释：CJK 字符按 1 字符 1 token，其余按 4 字符 1 token。
 * 它不追求与某个 tokenizer 完全一致，只用于“是否需要压缩”和“摘要是否收敛”
 * 两个保守判断；真实 usage 可用 {@link #anchor(long)} 覆盖启发式结果。</p>
 *
 * <p>纯函数、线程安全，不持有模型或网络状态。</p>
 */
public final class TokenEstimator {

    private static final int ASCII_CHARS_PER_TOKEN = 4;

    /**
     * 估算文本 token。
     *
     * @param text 文本，null 视为空
     * @return 估算值
     */
    public TokenEstimate estimate(String text) {
        if (text == null || text.isEmpty()) {
            return TokenEstimate.heuristic(0);
        }
        long[] chars = countChars(text);
        return estimateChars(chars[0], chars[1]);
    }

    /**
     * 按“已统计好的字符数”估算 token。
     *
     * <p>消息内容块、工具参数等已经逐块计算过字符总数，再拼成字符串只会浪费内存；
     * 这里按同一口径（每 4 个字符 1 token，向上取整）换算，并允许调用方传入
     * 已单独计数的 CJK 字符数，保证与 {@link #estimate(String)} 结果一致。</p>
     *
     * @param cjkChars CJK 字符数
     * @param otherChars 其他字符数
     * @return 估算值
     */
    public TokenEstimate estimateChars(long cjkChars, long otherChars) {
        long safeCjk = Math.max(0, cjkChars);
        long safeOther = Math.max(0, otherChars);
        long tokens = safeCjk + (safeOther + ASCII_CHARS_PER_TOKEN - 1) / ASCII_CHARS_PER_TOKEN;
        return TokenEstimate.heuristic(tokens > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) tokens);
    }

    /**
     * 统计文本中的 CJK 与其他字符数量。
     *
     * @param text 文本，null 视为空
     * @return 长度为 2 的数组：[CJK 字符数, 其他字符数]
     */
    long[] countChars(String text) {
        long cjk = 0;
        long other = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            if (isCjkLike(codePoint)) {
                cjk++;
            } else {
                other++;
            }
            index += Character.charCount(codePoint);
        }
        return new long[] {cjk, other};
    }

    /**
     * 用 provider 上报的输入 token 校准估算。
     *
     * <p>返回两者较大值：usage 反映的是上一次请求，本次上下文只会更长，
     * 取较大值可避免低估导致压缩触发过晚。</p>
     *
     * @param estimate 启发式估算
     * @param inputTokens provider 上报的输入 token
     * @return 校准后的估算；usage 不可用时返回原估算
     */
    public TokenEstimate anchor(TokenEstimate estimate, long inputTokens) {
        Objects.requireNonNull(estimate, "estimate 不能为空");
        if (inputTokens <= 0) {
            return estimate;
        }
        TokenEstimate anchored = TokenEstimate.anchored(inputTokens);
        return anchored.tokens() >= estimate.tokens() ? anchored : estimate;
    }

    /**
     * 是否为 CJK 或全角字符。
     *
     * @param codePoint Unicode 码点
     * @return true 表示按 1 字符 1 token 估算
     */
    static boolean isCjkLike(int codePoint) {
        return (codePoint >= 0x3000 && codePoint <= 0x303F)
                || (codePoint >= 0x3040 && codePoint <= 0x30FF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF);
    }
}
