package com.piagent.trigger.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine;

/**
 * CLI 的标准输入输出封装。
 *
 * <p>命令不直接使用 {@code System.out/System.in}，而是通过 picocli 注入的流，
 * 这样测试可以替换为内存流。读取失败或 EOF 一律返回 null，由调用方决定默认行为。</p>
 */
public final class CliTerminal {

    private static final ThreadLocal<InputStream> BOUND_INPUT = new ThreadLocal<>();

    private final BufferedReader in;
    private final PrintWriter out;
    private final PrintWriter err;

    CliTerminal(BufferedReader in, PrintWriter out, PrintWriter err) {
        this.in = in;
        this.out = out;
        this.err = err;
    }

    /**
     * 绑定本次执行的输入流。
     *
     * <p>picocli 只管理输出流，输入流由入口在执行期间绑定；命令在主线程同步执行，
     * 因此用 ThreadLocal 即可避免全局可变状态。</p>
     *
     * @param input 输入流
     */
    static void bind(InputStream input) {
        BOUND_INPUT.set(input);
    }

    /**
     * 解除输入流绑定。
     */
    static void unbind() {
        BOUND_INPUT.remove();
    }

    /**
     * 从 picocli 命令上下文创建终端。
     *
     * @param commandLine 当前命令行
     * @return 终端封装
     */
    public static CliTerminal of(CommandLine commandLine) {
        InputStream input = BOUND_INPUT.get();
        if (input == null) {
            input = System.in;
        }
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, Charset.defaultCharset()));
        return new CliTerminal(reader, commandLine.getOut(), commandLine.getErr());
    }

    /**
     * 标准输出。
     *
     * @return 输出 writer
     */
    public PrintWriter out() {
        return out;
    }

    /**
     * 标准错误。
     *
     * @return 错误 writer
     */
    public PrintWriter err() {
        return err;
    }

    /**
     * 打印一行普通输出。
     *
     * @param line 文本
     */
    public void println(String line) {
        out.println(line);
        out.flush();
    }

    /**
     * 打印一行诊断信息。
     *
     * @param line 文本
     */
    public void error(String line) {
        err.println(line);
        err.flush();
    }

    /**
     * 在标准输出上提示并读取一行。
     *
     * @param prompt 提示文本
     * @return 输入行；EOF 或读取失败时为 null
     */
    public String readLine(String prompt) {
        return readLine(out, prompt);
    }

    /**
     * 在指定 writer 上提示并读取一行。
     *
     * <p>{@code --json} 模式下提示必须走 stderr，避免污染 stdout 的 JSON。</p>
     *
     * @param target 提示输出
     * @param prompt 提示文本
     * @return 输入行；EOF 或读取失败时为 null
     */
    public String readLine(PrintWriter target, String prompt) {
        target.print(prompt);
        target.flush();
        try {
            return in.readLine();
        } catch (IOException error) {
            return null;
        }
    }

    /**
     * 输入已就绪时读取一行，不阻塞。
     *
     * @return 输入行；没有就绪数据时为 null
     */
    public String readReadyLine() {
        try {
            if (!in.ready()) {
                return null;
            }
            return in.readLine();
        } catch (IOException error) {
            return null;
        }
    }

    /**
     * 读取全部标准输入直到 EOF。
     *
     * @return 按行拼接的文本
     */
    public String readAll() {
        List<String> lines = new ArrayList<>();
        try {
            String line;
            while ((line = in.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException error) {
            return "";
        }
        return String.join("\n", lines);
    }
}
