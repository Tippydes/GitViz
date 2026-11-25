package edu.pbl.gitviz.service.git;

import java.util.ArrayList;
import java.util.List;

/**
 * 取消一次读取的信号。取消时执行注册过的动作，例如结束正在运行的 git 子进程（§2.3.2"取消"）。
 *
 * <p>动作只执行一次，且不应抛出异常；取消之后再注册的动作会立即执行。线程安全。
 */
public final class CancellationToken {

    private final List<Runnable> actions = new ArrayList<>();
    private boolean cancelled;

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /** 取消。重复调用无效。动作在调用线程上、不持有锁的情况下执行。 */
    public void cancel() {
        List<Runnable> toRun;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            toRun = List.copyOf(actions);
            actions.clear();
        }
        for (Runnable action : toRun) {
            action.run();
        }
    }

    /** 注册取消时要执行的动作；如果已经取消，立即执行。 */
    public void onCancel(Runnable action) {
        boolean alreadyCancelled;
        synchronized (this) {
            alreadyCancelled = cancelled;
            if (!alreadyCancelled) {
                actions.add(action);
            }
        }
        if (alreadyCancelled) {
            action.run();
        }
    }
}
