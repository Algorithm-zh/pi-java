package com.piagent.trigger.cli;

import java.io.PrintWriter;
import org.jline.terminal.Terminal;

/**
 * run 执行期间的 Ctrl+C 监听。
 *
 * <p>JLine 在 Windows 与 POSIX 上都会按 ISIG 把 Ctrl+C 转成
 * {@link Terminal.Signal#INT}，不会作为字节交给读取方，因此这里只注册信号
 * 处理器。审批需要读一整行输入时先 {@link #pause()}，把信号交还给
 * {@code LineReader} 自己处理，结束后再 {@link #resume()}。</p>
 */
final class TuiInterrupts implements AutoCloseable {

    private final Terminal terminal;
    private final CliRun run;
    private final PrintWriter out;
    private Terminal.SignalHandler previous;
    private boolean registered;
    private boolean aborted;

    private TuiInterrupts(Terminal terminal, CliRun run, PrintWriter out) {
        this.terminal = terminal;
        this.run = run;
        this.out = out;
    }

    /**
     * 启动监听。
     *
     * @param terminal 终端
     * @param run 当前 run
     * @param out 取消提示输出
     * @return 可暂停与关闭的监听器
     */
    static TuiInterrupts start(Terminal terminal, CliRun run, PrintWriter out) {
        TuiInterrupts interrupts = new TuiInterrupts(terminal, run, out);
        interrupts.resume();
        return interrupts;
    }

    /**
     * 暂停监听，交还 Ctrl+C 给行读取。
     */
    synchronized void pause() {
        if (!registered) {
            return;
        }
        terminal.handle(Terminal.Signal.INT,
                previous == null ? Terminal.SignalHandler.SIG_DFL : previous);
        registered = false;
    }

    /**
     * 恢复监听。
     */
    synchronized void resume() {
        if (registered) {
            return;
        }
        previous = terminal.handle(Terminal.Signal.INT, signal -> abort());
        registered = true;
    }

    /**
     * 停止监听并恢复原有信号处理器。
     */
    @Override
    public void close() {
        pause();
    }

    private void abort() {
        synchronized (this) {
            if (aborted) {
                return;
            }
            aborted = true;
        }
        try {
            run.abort();
            out.println("  [取消] 已请求停止，等待收尾…");
        } catch (RuntimeException error) {
            out.println("  [取消] 请求停止失败: " + error.getMessage());
        }
        out.flush();
    }
}
