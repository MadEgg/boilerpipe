/**
 * Copyright (C) 2013 Christian Kohlschütter (ckkohl79@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kohlschutter.boilerpipe.sax;

import com.kohlschutter.boilerpipe.BoilerpipeExtractor;
import com.kohlschutter.boilerpipe.BoilerpipeProcessingException;
import com.kohlschutter.boilerpipe.document.Image;
import com.kohlschutter.boilerpipe.document.Media;
import com.kohlschutter.boilerpipe.document.TextBlock;
import com.kohlschutter.boilerpipe.document.TextDocument;
import com.kohlschutter.boilerpipe.document.VimeoVideo;
import com.kohlschutter.boilerpipe.document.YoutubeVideo;

import org.apache.xerces.parsers.AbstractSAXParser;
import org.cyberneko.html.HTMLConfiguration;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts youtube and vimeo videos that are enclosed by extracted content.
 * 
 * @author Christian Kohlschütter, manuel.codiga@gmail.com
 */
public final class MediaExtractor {

	/**  */
	public static final MediaExtractor INSTANCE = new MediaExtractor();

	/**
	 * @return the singleton instance of {@link MediaExtractor}.
	 */
	public static MediaExtractor getInstance() {
		return INSTANCE;
	}

	/**
	 * Processes the given {@link TextDocument} and the original HTML text (as a String).
	 * 
	 * @param doc The processed {@link TextDocument}.
	 * @param origHTML The original HTML document.
	 * @return A List of enclosed {@link Image}s
	 * @throws BoilerpipeProcessingException if an error during extraction occure
	 */
	public List<Media> process(final TextDocument doc, final String origHTML) throws BoilerpipeProcessingException {
		return process(doc, new InputSource(new StringReader(origHTML)));
	}

	/**
	 * Processes the given {@link TextDocument} and the original HTML text (as an {@link InputSource}).
	 * 
	 * @param doc The processed {@link TextDocument}. The original HTML document.
	 * @return A List of enclosed {@link Image}s
	 * @throws BoilerpipeProcessingException
	 */
	public List<Media> process(final TextDocument doc, final InputSource is) throws BoilerpipeProcessingException {
		final Implementation implementation = new Implementation();
		implementation.process(doc, is);

		return implementation.linksHighlight;
	}

	/**
	 * Fetches the given {@link URL} using {@link HTMLFetcher} and processes the retrieved HTML using the specified
	 * {@link BoilerpipeExtractor}.
	 * 
	 * @param url the url of the document to fetch
	 * @param extractor extractor to use
	 * 
	 * @return A List of enclosed {@link Image}s
	 * @throws IOException
	 * @throws BoilerpipeProcessingException
	 * @throws SAXException
	 */
	@SuppressWarnings("javadoc")
	public List<Media> process(final URL url, final BoilerpipeExtractor extractor) throws IOException,
			BoilerpipeProcessingException, SAXException {
		final HTMLDocument htmlDoc = HTMLFetcher.fetch(url);

		BoilerpipeSAXInput saxInput = new BoilerpipeSAXInput(htmlDoc.toInputSource());
		final TextDocument doc = saxInput.getTextDocument(extractor.getHtmlParser());
		extractor.process(doc);

		final InputSource is = htmlDoc.toInputSource();

		return process(doc, is);
	}

	/**
	 * parses the media (picture, video) out of doc
	 * 
	 * @param doc document to parse the media out
	 * @param extractor extractor to use
	 * @return list of extracted media, with size = 0 if no media found
	 */
	public List<Media> process(String doc, final BoilerpipeExtractor extractor) {
		final HTMLDocument htmlDoc = new HTMLDocument(doc);
		List<Media> media = new ArrayList<Media>();
		TextDocument tdoc;

		try {
			BoilerpipeSAXInput saxInput = new BoilerpipeSAXInput(htmlDoc.toInputSource());
			tdoc = saxInput.getTextDocument(extractor.getHtmlParser());
			extractor.process(tdoc);
			final InputSource is = htmlDoc.toInputSource();
			media = process(tdoc, is);
		} catch (Exception e) {
			return null;
		}
		return media;
	}

	private final class Implementation extends AbstractSAXParser implements ContentHandler {
		List<Media> linksHighlight = new ArrayList<Media>();
		private List<Media> linksBuffer = new ArrayList<Media>();

		private int inIgnorableElement = 0;
		private int characterElementIdx = 0;
		private final BitSet contentBitSet = new BitSet();

		private boolean inHighlight = false;

		Implementation() {
			super(new HTMLConfiguration());
			setContentHandler(this);
		}

		void process(final TextDocument doc, final InputSource is) throws BoilerpipeProcessingException {
			for (TextBlock block : doc.getTextBlocks()) {
				if (block.isContent()) {
					final BitSet bs = block.getContainedTextElements();
					if (bs != null) {
						contentBitSet.or(bs);
					}
				}
			}

			try {
				parse(is);
			} catch (SAXException e) {
				throw new BoilerpipeProcessingException(e);
			} catch (IOException e) {
				throw new BoilerpipeProcessingException(e);
			}
		}

		public void endDocument() throws SAXException {
		}

		public void endPrefixMapping(String prefix) throws SAXException {
		}

		public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
		}

		public void processingInstruction(String target, String data) throws SAXException {
		}

		public void setDocumentLocator(Locator locator) {
		}

		public void skippedEntity(String name) throws SAXException {
		}

		public void startDocument() throws SAXException {
		}

		public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
			TagAction ta = TAG_ACTIONS.get(localName);
			if (ta != null) {
				ta.beforeStart(this, localName);
			}

			try {
				if (inIgnorableElement == 0) {
					if (inHighlight && "IFRAME".equalsIgnoreCase(localName)) {
						String src = atts.getValue("src");
						if (src != null) {
							src = src.replaceAll("\\\\\"", "");
						}
						if (src != null && src.length() > 0 && src.contains("youtube.com/embed/")) {
							String originUrl = null;
							if (!src.startsWith("http:")) {
								src = "http:" + src;
							}
							try {
								URL url = new URL(src);
								String path = url.getPath();
								String[] pathParts = path.split("/");
								originUrl = "http://www.youtube.com/watch?v=" + pathParts[pathParts.length - 1];
								linksBuffer.add(new YoutubeVideo(originUrl, src));
							} catch (MalformedURLException e) {
							}

						}

						if (src != null && src.length() > 0 && src.contains("player.vimeo.com")) {
							String originUrl = null;
							if (!src.startsWith("http:")) {
								src = "http:" + src;
							}
							try {
								URL url = new URL(src);
								String path = url.getPath();
								String[] pathParts = path.split("/");
								originUrl = "http://vimeo.com/" + pathParts[pathParts.length - 1];
								linksBuffer.add(new VimeoVideo(originUrl, src));
							} catch (MalformedURLException e) {
							}

						}
					}

					if (inHighlight && "IMG".equalsIgnoreCase(localName)) {
						String src = atts.getValue("src");
						try {
							URI image = new URI(src);
							if (src != null && src.length() > 0) {
								linksBuffer.add(new Image(src, atts.getValue("width"), atts.getValue("height"), atts
										.getValue("alt")));
							}
						} catch (URISyntaxException e) {
						}
					}
				}
			} finally {
				if (ta != null) {
					ta.afterStart(this, localName);
				}
			}
		}

		public void endElement(String uri, String localName, String qName) throws SAXException {
			TagAction ta = TAG_ACTIONS.get(localName);
			if (ta != null) {
				ta.beforeEnd(this, localName);
			}

			try {
				if (inIgnorableElement == 0) {
					//
				}
			} finally {
				if (ta != null) {
					ta.afterEnd(this, localName);
				}
			}
		}

		public void characters(char[] ch, int start, int length) throws SAXException {
			characterElementIdx++;
			if (inIgnorableElement == 0) {

				boolean highlight = contentBitSet.get(characterElementIdx);
				if (!highlight) {
					if (length == 0) {
						return;
					}
					boolean justWhitespace = true;
					for (int i = start; i < start + length; i++) {
						if (!Character.isWhitespace(ch[i])) {
							justWhitespace = false;
							break;
						}
					}
					if (justWhitespace) {
						return;
					}
				}

				inHighlight = highlight;
				if (inHighlight) {
					linksHighlight.addAll(linksBuffer);
					linksBuffer.clear();
				}
			}
		}

		public void startPrefixMapping(String prefix, String uri) throws SAXException {
		}

	}

	@SuppressWarnings("synthetic-access")
	private static final TagAction TA_IGNORABLE_ELEMENT = new TagAction() {
		@Override
		void beforeStart(final Implementation instance, final String localName) {
			instance.inIgnorableElement++;
		}

		@Override
		void afterEnd(final Implementation instance, final String localName) {
			instance.inIgnorableElement--;
		}
	};

	private static Map<String, TagAction> TAG_ACTIONS = new HashMap<String, TagAction>();
	static {
		TAG_ACTIONS.put("STYLE", TA_IGNORABLE_ELEMENT);
		TAG_ACTIONS.put("SCRIPT", TA_IGNORABLE_ELEMENT);
		TAG_ACTIONS.put("OPTION", TA_IGNORABLE_ELEMENT);
		TAG_ACTIONS.put("NOSCRIPT", TA_IGNORABLE_ELEMENT);
		TAG_ACTIONS.put("EMBED", TA_IGNORABLE_ELEMENT);
		TAG_ACTIONS.put("APPLET", TA_IGNORABLE_ELEMENT);
		TAG_ACTIONS.put("LINK", TA_IGNORABLE_ELEMENT);

		TAG_ACTIONS.put("HEAD", TA_IGNORABLE_ELEMENT);
	}

	private abstract static class TagAction {
		void beforeStart(final Implementation instance, final String localName) {
		}

		void afterStart(final Implementation instance, final String localName) {
		}

		void beforeEnd(final Implementation instance, final String localName) {
		}

		void afterEnd(final Implementation instance, final String localName) {
		}
	}
}
