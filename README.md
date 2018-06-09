# FutureTask
FutureTask源码解析

一、FutureTask是什么？
-----
<br>FutureTask是**可取消**的**异步**的计算任务，它可以通过线程池和Thread对象执行，一般来说FutureTask用于耗时的计算。

二、FutureTask继承图
----
![](https://github.com/Alooooha/FutureTask/blob/master/img/img1.png)

三、FutureTask源码
----
*  *FutureTask的七种状态*
 
|状态 (state)|值      |描述                    |        
|------------|-------|-------------------------|
|NEW         |   0   | 任务执行阶段，结果赋值前  |
|COMPLETING  |   1   | 结果赋值阶段             |
|NORMAL      |   2   | 任务执行完毕             |
|EXCEPTIONAL |   3   | 任务执行时发生异常       |
|CANCELLED   |   4   | 任务被取消               |
|INTERRUPTING|   5   | 设置中断变量阶段         |
|INRTERRUPTED|   6   | 任务中断                |

**可能出现的状态变化
 <br>NEW -> COMPLETING -> NORMAL 
 <br>NEW -> COMPLETING -> EXCEPTION 
 <br>NEW ->CANCELLED 
 <br>NEW -> INITERRUPTING -> INTERRUPTED**
*  *FutureTask的**变量参数***
<br>`private Object outcome;`
<br>任务返回值，正常返回时是泛型指定对象，任务异常时是Throwable对象，它在state=COMPLETING阶段完成赋值操作。
<br>`private volatile Thread runner;`
<br>当前执行任务线程，run()方法开始时进行判断和赋值，保证同一时刻只有一个线程执行FutureTask，并且FutureTask.run()只能执行一次。
<br>`private volatile WaitNode waiters;`
<br>阻塞队列头节点，每个节点存储调用FutureTask.get()方法，且采用LockSupport.park()阻塞的线程。在任务对outcome完成赋值后，调用finishCompletion()唤醒所有阻塞线程。

*  *FutureTask的**两个构造方法***
<br>`FtureTask(Callable<V> callable)`
<br>FutureTask.run()中调用callable.call()。
<br>`FutureTask(Runnable runnable, V result)`
<br>调用Executors.callable(runnable, result)，返回new RunnableAdapter<T>(task, result)，RunnableAdapter源码：
```
  static final class RunnableAdapter<T> implements Callable<T> {
        final Runnable task;
        final T result;
        RunnableAdapter(Runnable task, T result) {
            this.task = task;
            this.result = result;
        }
        public T call() {
            task.run();
            return result;
        }
    }
```
 
 * *FutureFask的**静态参数***
<br>`private static final long stateOffset;`
<br>state参数在内存中对象的偏移量
<br>`private static final long runnerOffset;`
<br>runner参数在内存中对象的偏移量
<br>`private static final long waitersOffset;`
<br>waiter参数在内存中对象的偏移量
<br>**赋值**：
```
  Class<?> k = FutureTask.class;
		stateOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("state"));
		runnerOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("runner"));
		waitersOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("waiters"));
```
<br>`private static final sun.misc.Unsafe ` **UNSAFE**;
<br>UNSAFE类提供高效并且线程安全方式操作变量，直接和内存数据打交道，但是分配的内存需要手动释放。由于UNSAFE类只提供给JVM信任的启动类加载器所使用，这里采用反射获取UNSAFE对象。
```
/**
* 通过反射的方式来访问Unsafe类中的theUnsafe静态成员变量，该theUnsafe静态成员变量在Unsafe第一次使用时就已经初始化。
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
```
* *FutureTask的**静态内部类***
<br>WaitNode:存储调用get()方法而阻塞的线程。
```
	/**
	 *将等待线程封装成节点，形成等待队列 
	 * @author BeiwEi
	 */
	static final class WaitNode {
		  volatile Thread thread;
		  volatile WaitNode next;
		  WaitNode() { 
	          thread = Thread.currentThread();
    }
	}
```
* ***LockSupport***
<br>LockSupport是用来创建锁和其他同步类的基本**线程阻塞**原语，park()和unpark()方法实现阻塞线程和解除线程阻塞，LockSupport和每个使用它的线程都与一个许可(permit)关联。permit相当于1，0的开关，默认是0，调用一次unpark就加1变成1，调用一次park会消费permit, 也就是将1变成0，同时park立即返回。再次调用park会变成block（因为permit为0了，会阻塞在这里，直到permit变为1）, 这时调用unpark会把permit置为1。每个线程都有一个相关的permit, permit最多只有一个，重复调用unpark也不会积累。

四、FutureTask方法（5个公共方法）
---
* *public boolean **isDown()***
```
	/**
	* 因为NEW表示任务执行阶段，因此判断任务是否完成
	*/
	public boolean isDone() {
		return state != NEW;
	}
```
* *public boolean **cancel(boolean mayInterruptIfRunning)***
```
		/**
		* 如果任务在NEW阶段，mayInterruptIfRunning == true，且能CAS能修改state值，中断当前线程，调用finishCompletion()
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
```
**finishCompletion()**
```
	/**
	 * 通过waiters参数，唤醒等待队列，所有因为调用get()方法而阻塞的线程
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
```
* *public boolean **isCancelled()***
```
	public boolean isCancelled() {
		return state >= CANCELLED;
	}
```
* *public V **get()***
```
	/**
	* 如果任务正在执行或者正在赋值，调用awaitDone()方法，阻塞当前线程，且封装成WaitNode，放入等待队列中
	* 否则，调用report()，将outcome包装成返回参数类型。
	*/
	public V get() throws InterruptedException, ExecutionException {
		int s = state;
		if (s <= COMPLETING) 
			s = awaitDone(false, 0L);
		return report(s);
	}
```
**awaitDone(boolean timed, long nanos)**
```
	/**
	 * 功能：制定线程等待结果的具体实行策略
	 * 
	 * 逻辑
	 * for中判断当前线程是否被中断，如果是抛出异常
	 * 
	 * 一、如果state == NEW（运行状态）
	 * 		将当前线程包装成WaitNode，并CAS放入队列头部。（期间如果状态改变，可能其中某些步骤未进行）
	 * 		如果timed == true，计算超时时间，已经超过设置时间，移除队列节点，返回
	 * 		未超过设置时间，阻塞线程到设置时间
	 * 		这几个操作是层级关系，如果一直保持state == NEW，将进行：
	 * 								创建WaitNode
	 * 								CAS放入队列头部
	 * 								超时移除节点并返回
	 * 								未超时调用parkNanos()阻塞线程
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
```
**report()**
```
	/**
	 * 功能：返回任务结果
	 * 描述： 由于在set(),setException() 方法中设置了otcome的值，
	 * 	 可能是throwable对象，也可能是正常返回结果，所以需要
	 * 	 对outcome进行一次处理
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
```
* *public V **get(long timeout, TimeUnit unit)***
```
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
```

五、FutureTask的run()方法
---
```
	/**
	 * 判断：状态是否为NEW，并CAS成功将runner线程赋值为当前线程
	 * 线程调用run(),进行判断，运行callable.call()，再调用set()将结果设置到outcome。
	 * 失败调用setException()，将Throwable对象设置到outcome。
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
```

六、使用场景
---
* *单线程执行任务，并调用get()获取结果*
```
	//A 实现了Callable接口
	FutureTask f = new FutureTask(new A());
	Thread t = new Thread(f);
	t.satrt();
	f.get();
```
<br>*分析代码流程:*
<br>线程调用f.run()，内部调用A.call()方法，f.runner = t，并取得结果，state == NEW，此时主线程执行到f.get()，由于任务还在执行，主线程被阻塞，并封装成WaitNode，插入等待队列头部，waiters = 主线程。回到t，结果被set到outcome中，state == NORMAL，调用finishCompletion()唤醒等待队列，此时f.get()结束阻塞，返回结果。
* *多线程执行任务，并同时调用get()获取结果*
```
	//A 实现Callable接口
	FutureTask f = new FutureTask(new A());
	Thread t1 = new Thread(f);
	Thread t2 = new Thread(f);
	Thread t3 = new Thread(f);
	t1.start();
	t2.start();
	t3.start();
	t1.get();
	t2.get();
	t3.get();
```
<br>*分析代码流程：*
<br>t1,t2,t3同时执行FutureTask，在run()中有：
```
	if (state != NEW || !UNSAFE.compareAndSwapObject(this, runnerOffset, null, Thread.currentThread()))
				return;
```
表明多线程同时执行FutureTask时，只有一个线程能执行run()方法，而且由于执行完毕，state不再等于NEW，run()方法无法再次被调用。
<br>后续和上面情况大体一致。
