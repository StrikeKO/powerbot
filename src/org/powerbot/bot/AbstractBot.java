package org.powerbot.bot;

import java.applet.Applet;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.InputEvent;
import java.io.Closeable;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.powerbot.Boot;
import org.powerbot.gui.BotChrome;
import org.powerbot.script.Bot;
import org.powerbot.script.Client;
import org.powerbot.script.ClientContext;
import org.powerbot.script.Condition;

public abstract class AbstractBot<C extends ClientContext<? extends Client>> extends Bot<C> implements Runnable, Closeable {
	public final BotChrome chrome;
	protected final Timer timer;
	public final EventDispatcher dispatcher;
	public final AtomicBoolean pending;
	private volatile AWTEventListener awtel;

	private final AtomicBoolean trapping;
	private final Map<String, byte[]> clazz;
	private final ClassFileTransformer trap;

	public AbstractBot(final BotChrome chrome, final EventDispatcher dispatcher) {
		this.chrome = chrome;
		timer = new Timer(true);
		this.dispatcher = dispatcher;
		pending = new AtomicBoolean(false);

		trapping = new AtomicBoolean(false);
		clazz = new ConcurrentHashMap<String, byte[]>();
		trap = new ClassFileTransformer() {
			@Override
			public byte[] transform(final ClassLoader loader, final String className, final Class<?> classBeingRedefined, final ProtectionDomain protectionDomain, final byte[] classfileBuffer) throws IllegalClassFormatException {
				if (className.indexOf('/') == -1 && loader.getParent().getClass().getCanonicalName().startsWith("app.")) {
					clazz.put(className, classfileBuffer);
				}
				return classfileBuffer;
			}
		};
	}

	protected abstract Map<String, byte[]> getClasses();

	protected abstract void reflect(final ReflectorSpec s);

	public final void trap() {
		if (!trapping.compareAndSet(false, true)) {
			return;
		}
		Boot.instrumentation.addTransformer(trap);
	}

	public final void untrap() {
		if (!trapping.compareAndSet(true, false)) {
			return;
		}
		Boot.instrumentation.removeTransformer(trap);
	}

	@Override
	public final void run() {
		trap();
		final Map<String, byte[]> c = getClasses();
		c.putAll(clazz);
		Condition.sleep(2500);
		untrap();

		final String hash = ClientTransform.hash(c);
		log.info("Hash: " + hash + " size: " + c.size());

		new Thread(dispatcher).start();
		Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
			@Override
			public void eventDispatched(final AWTEvent e) {
				final InputSimulator input = (InputSimulator) ctx.input;
				if (chrome.overlay.get() != null && e.getSource().equals(chrome.overlay.get()) && e instanceof InputEvent) {
					input.redirect(e);
					return;
				}

				final boolean b = input.eq.remove(e);
				final Component c = input.getComponent();
				if (c != null && e.getSource().equals(c) && !b) {
					dispatcher.dispatch(e);

					if (input.blocking()) {
						((InputEvent) e).consume();
					} else {
						input.processEvent(e);
					}
				}
			}
		}, AWTEvent.KEY_EVENT_MASK + AWTEvent.MOUSE_EVENT_MASK + AWTEvent.MOUSE_MOTION_EVENT_MASK + AWTEvent.MOUSE_WHEEL_EVENT_MASK);

		for (; ; ) {
			try {
				final ReflectorSpec s = ClientTransform.get(ctx.rtv(), hash);
				reflect(s);
			} catch (final IOException e) {
				if (e.getCause() instanceof IllegalStateException) {
					ClientTransform.submit(log, ctx.rtv(), hash, c);
					continue;
				}
			}
			break;
		}

		EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				chrome.menu.get().update();
			}
		});
	}

	@Override
	public void close() {
		ctx.controller.stop();
		if (Thread.currentThread().getContextClassLoader() instanceof ScriptClassLoader) {
			return;
		}

		if (awtel != null) {
			Toolkit.getDefaultToolkit().removeAWTEventListener(awtel);
			awtel = null;
		}

		timer.cancel();
		dispatcher.close();

		final Applet applet = (Applet) chrome.target.get();
		if (applet != null) {
			ctx.client(null);
		}

		chrome.bot.set(null);
	}
}
