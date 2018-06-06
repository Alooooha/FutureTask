package util.concurrent;

/**
 * 继承Runnable接口，可以被线程调用
 * @param <V>
 */
public interface RunnableFuture<V> extends Runnable, Future<V>{
	
	void run();
	
}
