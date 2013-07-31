package org.powerbot.bot.loader;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.powerbot.Configuration;
import org.powerbot.bot.loader.transform.TransformSpec;
import org.powerbot.util.StringUtil;
import org.powerbot.util.Tracker;
import org.powerbot.util.io.HttpClient;
import org.powerbot.util.io.IOHelper;

public class ClientLoader implements Runnable {
	private static final Logger log = Logger.getLogger(ClientLoader.class.getName());

	private final Map<String, byte[]> classes;
	public final Crawler crawler;
	public TransformSpec tspec;

	public ClientLoader() {
		classes = new HashMap<>();
		crawler = new Crawler();
	}

	@Override
	public void run() {
		log.info("Loading game");

		if (!crawler.crawl()) {
			throw new RuntimeException("please check your firewall and internet connection");
		}

		byte[] buffer;
		try {
			final URLConnection clientConnection = HttpClient.getHttpConnection(new URL(crawler.archive));
			clientConnection.addRequestProperty("Referer", crawler.game);
			buffer = IOHelper.read(HttpClient.getInputStream(clientConnection));
		} catch (IOException ignored) {
			buffer = null;
		}

		if (buffer == null || buffer.length == 0) {
			throw new RuntimeException("error downloading game");
		}
		final String[] keys = {crawler.parameters.get("0"), crawler.parameters.get("-1")};
		if (keys[0] == null || keys[1] == null) {
			throw new RuntimeException("error parsing parameters");
		}

		final byte[][] data = {Crypt.decode(keys[0]), Crypt.decode(keys[1])};
		classes.putAll(Deflator.extract(data[0], data[1], buffer));
		if (classes.size() == 0) {
			throw new RuntimeException("failed to decrypt inner.pack");
		}

		final String hash = StringUtil.byteArrayToHexString(Deflator.inner_pack_hash);
		log.info("Unpacking game (" + hash.substring(0, 6) + ")");

		tspec = null;
		while (true) {
			try {
				tspec = getSpec(hash);
				break;
			} catch (final IOException ignored) {
				break;
			} catch (final PendingException p) {
				int d = p.getDelay() / 1000;
				log.warning("Request pending, trying again in " + (d < 60 ? d + " seconds" : (int) Math.ceil(d / 60) + " minutes"));
				try {
					Thread.sleep(p.getDelay());
				} catch (final InterruptedException ignored) {
					break;
				}
			}
		}
		if (tspec == null) {
			throw new RuntimeException("error getting t-spec");
		}

		tspec.adapt();
	}

	public TransformSpec getTspec() {
		return tspec;
	}

	public Map<String, byte[]> classes() {
		final Map<String, byte[]> classes = new HashMap<>(this.classes.size());
		classes.putAll(this.classes);
		return classes;
	}

	private synchronized TransformSpec getSpec(final String packHash) throws IOException, PendingException {
		final int delay = 1000 * 60 * 3 + 30;
		final String pre = "loader/spec/" + packHash;
		int r;

		final byte[] b = new byte[16];
		final String keyAlgo = "ARCFOUR", cipherAlgo = "RC4", hashAlgo = "SHA1";

		final MessageDigest md;
		try {
			md = MessageDigest.getInstance(hashAlgo);
		} catch (final NoSuchAlgorithmException ignored) {
			return null;
		}

		md.update(StringUtil.getBytesUtf8(packHash));
		System.arraycopy(md.digest(), 0, b, 0, b.length);
		final SecretKey key = new SecretKeySpec(b, 0, b.length, keyAlgo);

		final HttpURLConnection con = HttpClient.getHttpConnection(new URL(String.format(Configuration.URLs.CLIENTPATCH, packHash)));
		con.setInstanceFollowRedirects(false);
		con.connect();
		r = con.getResponseCode();
		Tracker.getInstance().trackPage(pre, Integer.toString(r));
		if (r == HttpURLConnection.HTTP_OK) {
			final Cipher c;
			try {
				c = Cipher.getInstance(cipherAlgo);
				c.init(Cipher.DECRYPT_MODE, key);
			} catch (final GeneralSecurityException e) {
				throw new IOException(e);
			}
			return new TransformSpec(IOHelper.read(new CipherInputStream(HttpClient.getInputStream(con), c)));
		} else {
			final HttpURLConnection bucket = HttpClient.getHttpConnection(new URL(String.format(Configuration.URLs.CLIENTBUCKET, packHash)));
			bucket.addRequestProperty(String.format("x-%s-cv", Configuration.NAME.toLowerCase()), "100");
			bucket.setInstanceFollowRedirects(false);
			bucket.connect();
			r = bucket.getResponseCode();
			Tracker.getInstance().trackPage(pre + "/bucket", Integer.toString(r));
			switch (bucket.getResponseCode()) {
			case HttpURLConnection.HTTP_SEE_OTHER:
				final String dest = bucket.getHeaderField("Location");
				log.info("Updating client pack, please do not disconnect or close");
				final HttpURLConnection put = HttpClient.getHttpConnection(new URL(dest));
				put.setRequestMethod("PUT");
				put.setDoOutput(true);
				final Cipher c;
				try {
					c = Cipher.getInstance(cipherAlgo);
					c.init(Cipher.ENCRYPT_MODE, key);
				} catch (final GeneralSecurityException e) {
					throw new IOException(e);
				}
				final OutputStream out = new CipherOutputStream(new GZIPOutputStream(put.getOutputStream()), c);
				writePack(classes, out);
				out.flush();
				out.close();
				r = put.getResponseCode();
				put.disconnect();
				Tracker.getInstance().trackPage(pre + "/bucket/upload", Integer.toString(r));
				if (r == HttpURLConnection.HTTP_OK) {
					final HttpURLConnection bucket_notify = HttpClient.getHttpConnection(new URL(String.format(Configuration.URLs.CLIENTBUCKET, packHash)));
					bucket_notify.setRequestMethod("PUT");
					bucket_notify.connect();
					final int r_notify = bucket_notify.getResponseCode();
					bucket_notify.disconnect();
					if (r_notify == HttpURLConnection.HTTP_OK || r_notify == HttpURLConnection.HTTP_ACCEPTED) {
						throw new PendingException(delay * 2);
					} else {
						throw new RuntimeException("failed to load node manipulator; after reupload");
					}
				} else {
					throw new RuntimeException("failed to load node manipulator");
				}
			case HttpURLConnection.HTTP_ACCEPTED:
				throw new PendingException(delay);
			case HttpURLConnection.HTTP_BAD_REQUEST:
				Tracker.getInstance().trackPage(pre + "/bucket/failure", "");
				throw new RuntimeException("failed to update client pack");
			}
		}

		return null;
	}

	private void writePack(final Map<String, byte[]> classes, final OutputStream out) throws IOException {
		final int MAGIC = 0xC1A5700F, END_OF_FILE = 0x1;
		writeInt(MAGIC, out);

		synchronized (classes) {
			for (final byte[] data : classes.values()) {
				writeInt(data.length, out);
				out.write(data);
			}
		}

		writeInt(END_OF_FILE, out);
	}

	private void writeInt(final int v, final OutputStream o) throws IOException {
		final int m = 0xff;
		o.write(v >>> 24);
		o.write(v >>> 16 & m);
		o.write(v >>> 8 & m);
		o.write(v & m);
	}

	private final class PendingException extends Exception {
		private static final long serialVersionUID = -6937383190630297216L;
		private final int delay;

		public PendingException(final int delay) {
			this.delay = delay;
		}

		public int getDelay() {
			return delay;
		}
	}
}