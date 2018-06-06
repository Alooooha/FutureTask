package util.concurrent;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

/**
 * Future 唯一实现类
 * 
 * 两个构造方法： 1、传入Callable对象
 * 2、传入Runnable对象和result，使用Executor.callable()方法将runnable对象封装成Callable对象
 * 
 */
@SuppressWarnings("restriction")
public class FutureTask<V> implements RunnableFuture<V> {

	/**
	 * 可能的状态变化： NEW -> COMPLETING -> NORMAL NEW -> COMPLETING -> EXCEPTION NEW ->
	 * CANCELLED NEW -> INITERRUPTING -> INTERRUPTED
	 */
	private volatile int state;
	private static final int NEW = 0; // 初始化
	private static final int COMPLETING = 1; // 完成阶段，正在进行赋值操作
	private static final int NORMAL = 2; // 正常完成
	private static final int EXCEPTIONAL = 3; // 异常状态
	private static final int CANCELLED = 4; // 任务被取消
	private static final int INTERRUPTING = 5; // 设置中断变量，但是还未被中断
	private static final int INTERRUPTED = 6; // 任务被中断

	private Callable<V> callable;

	//返回的结果
	private Object outcome;
	private volatile Thread runner;
	private volatile WaitNode waiters;

	/**
	 * 功能：返回任务结果
	 * 描述：由于在set(),setException() 方法中设置了otcome的值，
	 * 		 可能是throwable对象，也可能是正常返回结果，所以需要
	 * 		对outcome进行一次处理
	 */
	@SuppressWarnings("unchecked")
	private V report(int s) throws ExecutionException {
		Object x = outcome;
		if (s == NORMAL)
			return (V)x;
		if (s >= CANCELLED)
			throw new CancellationException();
		throw new ExecutionException((Throwable)x);
	}
	
	public FutureTask(Callable<V> callable) {
		if (callable == null)
			throw new NullPointerException();
		this.callable = callable;
		this.state = NEW;
	}

	public FutureTask(Runnable runnable, V result) {
		this.callable = Executors.callable(runnable, result);
		this.state = NEW;
	}

	public boolean isCancelled() {
		return state >= CANCELLED;
	}

	public boolean isDone() {
		return state != NEW;
	}

	/**
	 * UNSAFE.compareAndSwapInt(this,stateOffset,NEW,XX); 
	 * 第一个参数：具体某个对象
	 * 第二个参数：对象的state属性在堆中对象的偏移量，通过偏移量可以修改属性的值 
	 * 第三个参数：state属性期望值，cas中与内存值做比较，相同即可修改
	 * 第四个参数：修改值
	 * 
	 * UNSAFE.putOrderedInt(this,stateOffset,INTERRUPTED): 
	 * 设置值并且马上写入主存，该变量必须是volatile类型 
	 * 第一个参数：目标对象 
	 * 第二个参数：属性的偏移地址 
	 * 第三个参数：修改值
	 * 
	 * 
	 * 
	 */
	public boolean cancel(boolean mayInterruptIfRunning) {
		/**
		 * 判断当前任务状态是否为NEW，并且cas操作能否成功修改state值，如果不能，返回false
		 */
		if (!(state == NEW
				&& UNSAFE.compareAndSwapInt(this, stateOffset, NEW, mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
			return false;
		try {
			if (mayInterruptIfRunning) {
				try {
					Thread t = runner;
					if (t != null)
						t.interrupt();
				} finally {
					// 修改state为INTERRUPTED状态
					UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
				}
			}
		} finally {
			//唤醒所有调用get方法等待线程队列
			finishCompletion();
		}
		return true;
	}
	
	public V get() throws InterruptedException, ExecutionException {
		int s = state;
		if (s <= COMPLETING) 
			s = awaitDone(false, 0L);
		return report(s);
	}
	
	/**
	 * 定时获取结果
	 */
	public V get(long timeout, TimeUnit unit)
		throws InterruptedException, ExecutionException, TimeoutException {
		if(unit == null)
			throw new NullPointerException();
		int s = state;
		if (s <= COMPLETING && (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
			throw new TimeoutException();
		return report(s);
	}
	
	
	protected void done() { }
	
	/**
	 * 将state设置为COMPLETING，result赋值到outcome，state设置为NORMAL
	 */
	protected void set(V v) {
		if(UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
			outcome = v;
			UNSAFE.putOrderedInt(this, stateOffset, NORMAL);
			finishCompletion();
		}
	}
	
	/**
	 * 将state设置为COMPLETING，throwable赋值到outcome，state设置为NORMAL
	 */
	protected void setException(Throwable t) {
		if(UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
			outcome = t;
			UNSAFE.putOrderedInt(this, stateOffset,EXCEPTIONAL);
			finishCompletion();
		}
	}
	
	/**
	 * 判断：状态是否为NEW，并CAS成功将runner线程赋值为当前线程
	 * 线程调用run(),进行判断，运行callable.call()，再调用set()将结果设置到result。
	 * 失败调用setException().
	 * 
	 */
	public void run() {
		if (state != NEW ||
			 !UNSAFE.compareAndSwapObject(this, runnerOffset, null, Thread.currentThread()))
				return;
		try {
			Callable<V> c = callable;
			if (c != null && state == NEW) {
				V result;
				boolean ran;
				try {
					result = c.call();
					ran = true;
				} catch(Throwable ex) {
					result = null;
					ran = false;
					setException(ex);
				}
				if(ran)
					set(result);
			}
		} finally {
			runner = null;
			int s = state;
			if(s >= INTERRUPTING)
				handlePossibleCancellationInterrupt(s);
		}
	}
	
	/**
	 * 如果当前state状态为INTERRUPTING，当前线程让出cpu，其他线程执行
	 */
	private void handlePossibleCancellationInterrupt(int s) {
		if(s == INTERRUPTING)
			while(state == INTERRUPTING)
				Thread.yield();
	}
	

	/**
	 *将等待线程封装成节点，形成等待队列 
	 * @author BeiwEi
	 */
	static final class WaitNode {
		volatile Thread thread;
		volatile WaitNode next;
		WaitNode() { thread = Thread.currentThread();}
	}
	
	/**
	 * 唤醒等待队列中，所有调用get()方法的线程
	 * LockSupport.unpark():线程取消阻塞
	 */
	private void finishCompletion() {
		for(WaitNode q; (q = waiters) != null;) {
			if(UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
				for(;;) {
					Thread t = q.thread;
					if(t != null) {
						q.thread = null;
						LockSupport.unpark(t);
					}
					WaitNode next = q.next;
					if(next == null)
						break;
					q.next = null;
					q = next;
				}
				break;
			}
		}
		done();
		
		callable = null;
	}
	
	/**
	 * 功能：制定线程等待结果的具体实行策略
	 * 
	 * 逻辑
	 * for中判断当前线程是否被中断，如果是抛出异常
	 * 
	 * 一、如果state == NEW（运行状态）
	 * 		将当前线程包装成WaitNode，并CAS放入队列头部。（期间如果状态改变，可能其中某些步骤未进行）
	 * 		如果timed == true，计算超时时间，已经超过设置时间，移除队列节点，返回
	 * 										未超过设置时间，阻塞线程到设置时间
	 * 		这几个操作是层级关系，如果一直保持state == NEW，将进行：
	 * 											创建WaitNode
	 * 											CAS放入队列头部
	 * 											超时移除节点并返回
	 * 											未超时调用parkNanos()阻塞线程
	 * 二、如果state == COMPLETING（赋值阶段）
	 * 		当前线程yield(),等待状态更新
	 * 
	 * 三、如果state > COMPLETING
	 * 		将等待队列中该线程节点的thread参数设置为null
	 * 
	 * System.nanoTime :返回的可能是任意时间，甚至为负，相对于System.concurrentTime更加精确。
	 * 					用于记录一个时间段。
	 * 
	 * 返回结果：state
	 * 		state <= COMPLETING,表示获取结果失败，可能是超时，可能是执行错误
	 * 		state > COMPLETING，表示获取结果成功
	 */
	private int awaitDone(boolean timed, long nanos) throws InterruptedException {
		final long deadline = timed ? System.nanoTime() + nanos : 0L;
		WaitNode q = null;
		boolean queued = false;
		for(;;) {
			//判断当前线程是否被中断,中断将移除等待队列中的
			if (Thread.interrupted()) {
				removeWaiter(q);
				throw new InterruptedException();
			}
			
			int s = state;
			if(s > COMPLETING) {
				if ( q != null)
					q.thread = null;
				return s;
			}
			else if (s == COMPLETING)
				Thread.yield();
			else if (q == null)
				q = new WaitNode();
			else if (!queued)
				//在队列头部添加q，并将q赋值给waiters
				queued = UNSAFE.compareAndSwapObject(this, waitersOffset, q.next = waiters, q);
			else if (timed) {
				nanos = deadline - System.nanoTime();
				//nanos为负，允许返回
				if (nanos <= 0L) {
					removeWaiter(q);
					return state;
				}
				//将线程阻塞，设置阻塞时间为nanos
				LockSupport.parkNanos(this, nanos);
			}
			else
				LockSupport.park(this);
		}
		
	}
	
	/**
	 * 功能：将指定节点从队列中移除
	 * @param node
	 */
	private void removeWaiter(WaitNode node) {
		if (node != null ) {
			node.thread = null;
			retry:
			for (;;) {
				for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
					s = q.next;
					if (q.thread != null)
						pred = q;
					else if (pred != null) {
						pred.next = s;
						if (pred.thread == null)
							continue retry;
					}
					else if (!UNSAFE.compareAndSwapObject(this, waitersOffset, q, s))
						continue retry;
				}
				break;
			}
		}
		
	}
	
	/**
	 * 通过反射获取Unsafe对象，因为getUnsafe()方法提供给受信任的类进行调用，否则报错
	 * 
	 * 创建sun.misc.Unsafe对象，该类提供线程安全和高效的方式修改变量，它直接与JVM内存打交道
	 * 
	 * 
	 * stateOffset：state变量在类结构中的偏移量
	 * runnerOffset：runner变量在类结构中的偏移量
	 * waitersOffset：waiter变量在类结构中的偏移量
	 * 
	 */
	private static final sun.misc.Unsafe UNSAFE;
	private static final long stateOffset;
	private static final long runnerOffset;
	private static final long waitersOffset;
	static {
		try {
//			UNSAFE = sun.misc.Unsafe.getUnsafe();
			UNSAFE = getUnsafe();
			Class<?> k = FutureTask.class;
			stateOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("state"));
			runnerOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("runner"));
			waitersOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("waiters"));
		} catch (Exception e) {
			throw new Error(e);
		}
	}
	
	/**
	* 通过反射获得Unsafe对象
	*/
	private static sun.misc.Unsafe getUnsafe() {

		Class<?> unsafeClass = sun.misc.Unsafe.class;
		try {
			for (Field f : unsafeClass.getDeclaredFields()) {
				if ("theUnsafe".equals(f.getName())) {
					f.setAccessible(true);
					return (sun.misc.Unsafe)f.get(null);
				}
			}
		}catch(Exception e) {
			e.printStackTrace();
		}
		return null;
		}

}
