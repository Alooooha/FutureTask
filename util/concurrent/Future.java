package util.concurrent;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 *	 
 * @author BeiwEi
 *	五个方法：
 *		boolean cannel(boolean mayInterruptIfRunning) : 任务取消
 *		boolean isCancelled() : 任务是否被取消
 *		boolean isDone() : 任务是否完成
 *		V get() : 取得任务结果（阻塞）
 *		V get(long timeout, TimeUnit unit) : 取得任务结果，定时
 *
 * @param <V>
 */
public interface Future<V> {

	boolean cancel(boolean mayInterruptIfRunning);
	
	boolean isCancelled();
	
	boolean isDone();
	
	V get() throws InterruptedException,ExecutionException;
	
	V get(long timeout, TimeUnit unit)
		throws InterruptedException, ExecutionException, TimeoutException;
	
}
