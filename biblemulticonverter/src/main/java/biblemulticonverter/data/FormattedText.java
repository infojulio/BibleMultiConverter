package biblemulticonverter.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents formatted text, that may contain headlines, footnotes, etc.
 */
public class FormattedText {

	/**
	 * Text used at the beginning of a footnote that should be actually a list
	 * of cross references.
	 */
	public static String XREF_MARKER = "\u2118 ";

	private List<Headline> headlines = new ArrayList<Headline>(0);
	private List<FormattedElement> elements = new ArrayList<FormattedElement>(5);
	private boolean finished = false;

	public Visitor<RuntimeException> getAppendVisitor() {
		if (finished)
			throw new IllegalStateException();
		return new AppendVisitor(this);
	}

	public <T extends Throwable> void accept(Visitor<T> visitor) throws T {
		if (visitor == null)
			return;
		String elementTypes = null;
		while (true) {
			int depth = visitor.visitElementTypes(elementTypes);
			if (depth <= 0)
				break;
			elementTypes = getElementTypes(depth);
		}
		for (Headline headline : headlines) {
			headline.acceptThis(visitor);
		}
		visitor.visitStart();
		for (FormattedElement element : elements)
			element.acceptThis(visitor);
		if (visitor.visitEnd())
			accept(visitor);
	}

	public List<Headline> getHeadlines() {
		return new ArrayList<Headline>(headlines);
	}

	public List<FormattedText> splitContent(boolean includeHeadlines, boolean innerContent) {
		List<FormattedText> result = new ArrayList<FormattedText>();
		if (includeHeadlines) {
			for (Headline h : headlines) {
				FormattedText t = new FormattedText();
				if (innerContent) {
					h.accept(t.getAppendVisitor());
				} else {
					h.acceptThis(t.getAppendVisitor());
				}
				result.add(t);
			}
		}
		for (FormattedElement e : elements) {
			FormattedText t = new FormattedText();
			if (innerContent) {
				if (e instanceof FormattedText)
					((FormattedText) e).accept(t.getAppendVisitor());
			} else {
				e.acceptThis(t.getAppendVisitor());
			}
			result.add(t);
		}
		return result;
	}

	public void validate(Bible bible, List<String> danglingReferences) {
		if (!finished)
			throw new IllegalStateException("Formatted text not marked as finished - this may dramatically increase memory usage!");
		accept(new ValidatingVisitor(bible, danglingReferences, this instanceof Verse ? ValidationContext.VERSE : ValidationContext.NORMAL_TEXT));
	}

	public void trimWhitespace() {
		if (finished)
			throw new IllegalStateException();
		if (Boolean.getBoolean("biblemulticonverter.keepwhitespace"))
			return;
		boolean trimmed = false;
		for (int i = 0; i < elements.size(); i++) {
			if (elements.get(i) instanceof FormattedText)
				((FormattedText) elements.get(i)).trimWhitespace();
			if (!(elements.get(i) instanceof Text))
				continue;
			if (i > 0 && elements.get(i - 1) instanceof Text) {
				elements.set(i, new Text((((Text) elements.get(i - 1)).text + ((Text) elements.get(i)).text).replace("  ", " ")));
				elements.remove(i - 1);
				i -= 2;
				continue;
			}
			Text text = (Text) elements.get(i);
			if (text.text.startsWith(" ")) {
				boolean trim;
				if (i == 0) {
					trim = true;
				} else {
					FormattedElement prev = elements.get(i - 1);
					trim = (prev instanceof LineBreak || prev instanceof Headline);
				}
				if (trim) {
					trimmed = true;
					if (text.text.length() == 1) {
						elements.remove(i);
						i--;
						continue;
					} else {
						elements.set(i, new Text(text.text.substring(1)));
					}
				}
			}
			if (text.text.endsWith(" ")) {
				boolean trim;
				if (i == elements.size() - 1) {
					trim = true;
				} else {
					FormattedElement next = elements.get(i + 1);
					trim = (next instanceof LineBreak || next instanceof Headline);
				}
				if (trim) {
					trimmed = true;
					if (text.text.length() == 1) {
						elements.remove(i);
						i--;
					} else {
						elements.set(i, new Text(text.text.substring(0, text.text.length() - 1)));
					}
				}
			}
		}
		if (trimmed)
			trimWhitespace();
	}

	/**
	 * Return the types of elements inside this formatted text as String, useful
	 * for regex matching.
	 */
	public String getElementTypes(int depth) {
		StringBuilder sb = new StringBuilder();
		accept(new ElementTypeVisitor(sb, depth, ""));
		return sb.toString();
	}

	public void removeLastElement() {
		if (finished)
			throw new IllegalStateException();
		elements.remove(elements.size() - 1);
	}

	/**
	 * Call this when the content of this object is complete. After calling this
	 * method, changes to the content are impossible. Note that this
	 * implementation takes measures (like share common objects) to reduce
	 * memory consumption; therefore, call this method if you are sure you do
	 * not have to change the contents again.
	 */
	public void finished() {
		if (finished)
			throw new IllegalStateException();
		finished = true;
		if (elements.size() == 0) {
			elements = Collections.emptyList();
		} else {
			for (FormattedElement e : elements) {
				if (e instanceof FormattedText)
					((FormattedText) e).finished();
			}
			((ArrayList<FormattedElement>) elements).trimToSize();
		}
		if (headlines.size() == 0) {
			headlines = Collections.emptyList();
		} else {
			for (Headline h : headlines)
				h.finished();
			((ArrayList<Headline>) headlines).trimToSize();
		}
	}

	private static interface FormattedElement {
		public abstract <T extends Throwable> void acceptThis(Visitor<T> v) throws T;
	}

	private static abstract class FormattedTextElement extends FormattedText implements FormattedElement {
	}

	private static class Text implements FormattedElement {
		private final String text;

		private Text(String text) {
			this.text = Utils.validateString("text", text, " | ?" + Utils.NORMALIZED_WHITESPACE_REGEX + " ?");
		}

		@Override
		public <T extends Throwable> void acceptThis(Visitor<T> v) throws T {
			v.visitText(text);
		}
	}

	public static class Headline extends FormattedTextElement {
		private final int depth;

		public Headline(int depth) {
			this.depth = Utils.validateNumber("depth", depth, 1, 9);
		}

		public int getDepth() {
			return depth;
		}

		public <T extends Throwable> void acceptThis(Visitor<T> visitor) throws T {
			accept(visitor.visitHeadline(depth));
		}
	}

	private static class Footnote extends FormattedTextElement {
		public <T extends Throwable> void acceptThis(Visitor<T> visitor) throws T {
			accept(visitor.visitFootnote());
		}
	}

	private static class CrossReference extends FormattedTextElement {
		private String bookAbbr;
		private BookID book;
		private int firstChapter;
		private String firstVerse;
		private int lastChapter;
		private String lastVerse;

		private CrossReference(String bookAbbr, BookID book, int firstChapter, String firstVerse, int lastChapter, String lastVerse) {
			this.bookAbbr = Utils.validateString("bookAbbr", bookAbbr, Utils.BOOK_ABBR_REGEX);
			this.book = Utils.validateNonNull("book", book);
			this.firstChapter = Utils.validateNumber("firstChapter", firstChapter, 1, Integer.MAX_VALUE);
			this.firstVerse = Utils.validateString("firstVerse", firstVerse, Utils.VERSE_REGEX);
			this.lastChapter = Utils.validateNumber("lastChapter", lastChapter, firstChapter, Integer.MAX_VALUE);
			this.lastVerse = Utils.validateString("lastVerse", lastVerse, Utils.VERSE_REGEX);
		}

		public <T extends Throwable> void acceptThis(Visitor<T> visitor) throws T {
			accept(visitor.visitCrossReference(bookAbbr, book, firstChapter, firstVerse, lastChapter, lastVerse));
		}
	}

	private static class FormattingInstruction extends FormattedTextElement {
		private final FormattingInstructionKind kind;

		private FormattingInstruction(FormattingInstructionKind kind) {
			this.kind = Utils.validateNonNull("kind", kind);
		}

		public <T extends Throwable> void acceptThis(Visitor<T> visitor) throws T {
			accept(visitor.visitFormattingInstruction(kind));
		}
	}

	private static class CSSFormatting extends FormattedTextElement {
		private final String css;

		private CSSFormatting(String css) {
			this.css = Utils.validateString("css", css, "[^\r\n\t\"<>&]*+");
		}

		public <T extends Throwable> void acceptThis(Visitor<T> visitor) throws T {
			accept(visitor.visitCSSFormatting(css));
		}
	}

	private static class VerseSeparator implements FormattedElement {
		@Override
		public <T extends Throwable> void acceptThis(Visitor<T> v) throws T {
			v.visitVerseSeparator();
		}
	}

	private static class LineBreak implements FormattedElement {
		private final LineBreakKind kind;

		private LineBreak(LineBreakKind kind) {
			this.kind = Utils.validateNonNull("kind", kind);
		}

		@Override
		public <T extends Throwable> void acceptThis(Visitor<T> v) throws T {
			v.visitLineBreak(kind);
		}
	}

	private static class GrammarInformation extends FormattedTextElement {
		private final int[] strongs;
		private final String[] rmac;
		private final int[] sourceIndices;

		private GrammarInformation(int[] strongs, String[] rmac, int[] sourceIndices) {
			this.strongs = strongs;
			this.rmac = rmac;
			this.sourceIndices = sourceIndices;
			if (strongs == null || strongs.length == 0) {
				throw new IllegalArgumentException("Strongs may not be empty");
			}
			for (int strong : strongs) {
				if (strong <= 0)
					throw new IllegalArgumentException("Strongs must be positive: " + strong);
			}
			if (rmac == null) {
				if (sourceIndices != null)
					throw new IllegalArgumentException("Source indices may not be present if rmac is missing");
			} else {
				if (rmac.length != strongs.length)
					throw new IllegalArgumentException("RMAC and Strongs have to be same length");
				for (String entry : rmac) {
					Utils.validateString("rmac", entry, Utils.RMAC_REGEX);
				}
				if (sourceIndices != null) {
					if (sourceIndices.length != strongs.length)
						throw new IllegalArgumentException("Source indices and strongs have to be same length");
					for (int idx : sourceIndices) {
						if (idx <= 0 || idx > 100)
							throw new IllegalArgumentException("Source index out of range: " + idx);
					}
				}
			}
		}

		public <T extends Throwable> void acceptThis(Visitor<T> visitor) throws T {
			accept(visitor.visitGrammarInformation(strongs, rmac, sourceIndices));
		}
	}

	private static class DictionaryEntry extends FormattedTextElement {
		private final String dictionary;
		private final String entry;

		private DictionaryEntry(String dictionary, String entry) {
			this.dictionary = Utils.validateString("dictionary", dictionary, "[A-Za-z0-9]+");
			if (dictionary.equals("strongs") || dictionary.equals("rmac"))
				throw new IllegalArgumentException("Please use Grammar information for Strongs and/or RMAC");
			this.entry = Utils.validateString("entry", entry, "[A-Za-z0-9-]+");
		}

		public <T extends Throwable> void acceptThis(Visitor<T> visitor) throws T {
			accept(visitor.visitDictionaryEntry(dictionary, entry));
		}
	}

	private static class RawHTML implements FormattedElement {

		private final RawHTMLMode mode;
		private final String raw;

		private RawHTML(RawHTMLMode mode, String raw) {
			this.mode = Utils.validateNonNull("mode", mode);
			this.raw = Utils.validateString("raw", raw, Utils.NORMALIZED_WHITESPACE_REGEX);
		}

		public <T extends Throwable> void acceptThis(Visitor<T> visitor) throws T {
			visitor.visitRawHTML(mode, raw);
		}
	}

	private static class VariationText extends FormattedTextElement {
		private final String[] variations;

		private VariationText(String[] variations) {
			this.variations = Utils.validateNonNull("variations", variations);
			if (variations.length == 0)
				throw new IllegalArgumentException("Variations is empty");
			for (String variation : variations) {
				Utils.validateString("variation", variation, "[A-Za-z0-9-]+");
			}
		}

		public <T extends Throwable> void acceptThis(Visitor<T> visitor) throws T {
			accept(visitor.visitVariationText(variations));
		}
	}

	private static class ExtraAttribute extends FormattedTextElement {
		private final ExtraAttributePriority prio;
		private final String category;
		private final String key;
		private final String value;

		private ExtraAttribute(ExtraAttributePriority prio, String category, String key, String value) {
			this.prio = Utils.validateNonNull("prio", prio);
			this.category = Utils.validateString("category", category, "[a-z0-9]+");
			this.key = Utils.validateString("key", key, "[a-z0-9-]+");
			this.value = Utils.validateString("value", value, "[A-Za-z0-9-]+");
		}

		public <T extends Throwable> void acceptThis(Visitor<T> visitor) throws T {
			accept(visitor.visitExtraAttribute(prio, category, key, value));
		}
	}

	public static enum FormattingInstructionKind {
		BOLD('b', "b", "font-weight: bold;"),
		ITALIC('i', "i", "font-style: italic;"),
		UNDERLINE('u', "u", "text-decoration: underline;"),
		LINK('l', null, "color: blue;"),
		// has to contain a footnote and may be used to render a link text for
		// it (covered range)
		FOOTNOTE_LINK('f', null, "color: blue;"),
		SUBSCRIPT('s', "sub", "font-size: .83em; vertical-align: sub;"),
		SUPERSCRIPT('p', "sup", "font-size: .83em; vertical-align: super;"),
		DIVINE_NAME('d', null, "font-variant: small-caps;"),
		STRIKE_THROUGH('t', null, "text-decoration: line-through;"),
		WORDS_OF_JESUS('w', null, "color: red;");

		private final char code;
		private final String htmlTag;
		private final String css;

		private FormattingInstructionKind(char code, String htmlTag, String css) {
			if (code < 'a' || code > 'z')
				throw new IllegalStateException("Invalid code: " + code);
			this.code = code;
			this.htmlTag = htmlTag;
			this.css = css;
		}

		public char getCode() {
			return code;
		}

		public String getHtmlTag() {
			return htmlTag;
		}

		public String getCss() {
			return css;
		}

		public static FormattingInstructionKind fromChar(char c) {
			for (FormattingInstructionKind k : values()) {
				if (k.code == c)
					return k;
			}
			throw new IllegalArgumentException("Char: " + c);
		}
	}

	public static enum LineBreakKind {
		PARAGRAPH, NEWLINE, NEWLINE_WITH_INDENT;
	}

	public static enum RawHTMLMode {
		ONLINE, OFFLINE, BOTH;
	}

	public static enum ExtraAttributePriority {
		KEEP_CONTENT, SKIP, ERROR;

		public <T extends Throwable> Visitor<T> handleVisitor(String category, Visitor<T> visitor) throws T
		{
			switch (this) {
			case ERROR:
				throw new IllegalArgumentException("Unhandled extra attribute of category " + category);
			case KEEP_CONTENT:
				return null;
			case SKIP:
				return visitor;
			default:
				throw new IllegalStateException("Unsupported priority: " + this);
			}
		}
	}

	public static interface Visitor<T extends Throwable> {

		/**
		 * @param elementTypes
		 *            Element types (initially {@code null})
		 * @return desired depth of element types, or 0 if sufficient
		 */
		public int visitElementTypes(String elementTypes) throws T;

		public Visitor<T> visitHeadline(int depth) throws T;

		public void visitStart() throws T;

		public void visitText(String text) throws T;

		public Visitor<T> visitFootnote() throws T;

		public Visitor<T> visitCrossReference(String bookAbbr, BookID book, int firstChapter, String firstVerse, int lastChapter, String lastVerse) throws T;

		public Visitor<T> visitFormattingInstruction(FormattingInstructionKind kind) throws T;

		public Visitor<T> visitCSSFormatting(String css) throws T;

		public void visitVerseSeparator() throws T;

		public void visitLineBreak(LineBreakKind kind) throws T;

		public Visitor<T> visitGrammarInformation(int[] strongs, String[] rmac, int[] sourceIndices) throws T;

		public Visitor<T> visitDictionaryEntry(String dictionary, String entry) throws T;

		public void visitRawHTML(RawHTMLMode mode, String raw) throws T;

		public Visitor<T> visitVariationText(String[] variations) throws T;

		public Visitor<T> visitExtraAttribute(ExtraAttributePriority prio, String category, String key, String value) throws T;

		/**
		 * @return whether to visit the element again
		 */
		public boolean visitEnd() throws T;
	}

	public static class VisitorAdapter<T extends Throwable> implements Visitor<T> {

		private Visitor<T> next;

		public VisitorAdapter(Visitor<T> next) throws T {
			this.next = next;
		}

		protected Visitor<T> getVisitor() throws T {
			return next;
		}

		protected Visitor<T> wrapChildVisitor(Visitor<T> childVisitor) throws T {
			return childVisitor;
		}

		protected void beforeVisit() throws T {
		}

		@Override
		public int visitElementTypes(String elementTypes) throws T {
			return getVisitor() == null ? 0 : getVisitor().visitElementTypes(elementTypes);
		}

		@Override
		public Visitor<T> visitHeadline(int depth) throws T {
			beforeVisit();
			return wrapChildVisitor(getVisitor() == null ? null : getVisitor().visitHeadline(depth));
		}

		@Override
		public void visitStart() throws T {
			if (getVisitor() != null)
				getVisitor().visitStart();
		}

		@Override
		public void visitText(String text) throws T {
			beforeVisit();
			if (getVisitor() != null)
				getVisitor().visitText(text);
		}

		@Override
		public Visitor<T> visitFootnote() throws T {
			beforeVisit();
			return wrapChildVisitor(getVisitor() == null ? null : getVisitor().visitFootnote());
		}

		@Override
		public Visitor<T> visitCrossReference(String bookAbbr, BookID book, int firstChapter, String firstVerse, int lastChapter, String lastVerse) throws T {
			beforeVisit();
			return wrapChildVisitor(getVisitor() == null ? null : getVisitor().visitCrossReference(bookAbbr, book, firstChapter, firstVerse, lastChapter, lastVerse));
		}

		@Override
		public Visitor<T> visitFormattingInstruction(FormattingInstructionKind kind) throws T {
			beforeVisit();
			return wrapChildVisitor(getVisitor() == null ? null : getVisitor().visitFormattingInstruction(kind));
		}

		@Override
		public Visitor<T> visitCSSFormatting(String css) throws T {
			beforeVisit();
			return wrapChildVisitor(getVisitor() == null ? null : getVisitor().visitCSSFormatting(css));
		}

		@Override
		public void visitVerseSeparator() throws T {
			beforeVisit();
			if (getVisitor() != null)
				getVisitor().visitVerseSeparator();
		}

		@Override
		public void visitLineBreak(LineBreakKind kind) throws T {
			beforeVisit();
			if (getVisitor() != null)
				getVisitor().visitLineBreak(kind);
		}

		@Override
		public Visitor<T> visitGrammarInformation(int[] strongs, String[] rmac, int[] sourceIndices) throws T {
			beforeVisit();
			return wrapChildVisitor(getVisitor() == null ? null : getVisitor().visitGrammarInformation(strongs, rmac, sourceIndices));
		}

		@Override
		public Visitor<T> visitDictionaryEntry(String dictionary, String entry) throws T {
			beforeVisit();
			return wrapChildVisitor(getVisitor() == null ? null : getVisitor().visitDictionaryEntry(dictionary, entry));
		}

		@Override
		public void visitRawHTML(RawHTMLMode mode, String raw) throws T {
			beforeVisit();
			if (getVisitor() != null)
				getVisitor().visitRawHTML(mode, raw);
		}

		@Override
		public Visitor<T> visitVariationText(String[] variations) throws T {
			beforeVisit();
			return wrapChildVisitor(getVisitor() == null ? null : getVisitor().visitVariationText(variations));
		}

		@Override
		public Visitor<T> visitExtraAttribute(ExtraAttributePriority prio, String category, String key, String value) throws T {
			beforeVisit();
			return wrapChildVisitor(getVisitor() == null ? null : getVisitor().visitExtraAttribute(prio, category, key, value));
		}

		@Override
		public boolean visitEnd() throws T {
			return getVisitor() == null ? false : getVisitor().visitEnd();
		}
	}

	private static class AppendVisitor implements Visitor<RuntimeException> {
		private FormattedText target;

		public AppendVisitor(FormattedText target) {
			this.target = target;
		}

		@Override
		public int visitElementTypes(String elementTypes) throws RuntimeException {
			return 0;
		}

		@Override
		public Visitor<RuntimeException> visitHeadline(int depth) {
			Headline h = new Headline(depth);
			if (target.elements.size() == 0)
				target.headlines.add(h);
			else
				target.elements.add(h);
			return new AppendVisitor(h);
		}

		@Override
		public void visitStart() {
		}

		@Override
		public void visitText(String text) throws RuntimeException {
			if (text.length() == 0)
				return;
			if (target.elements.size() > 0 && target.elements.get(target.elements.size() - 1) instanceof Text) {
				Text oldText = (Text) target.elements.remove(target.elements.size() - 1);
				if (oldText.text.endsWith(" ") && text.startsWith(" "))
					text = text.substring(1);
				text = oldText.text + text;
			}
			target.elements.add(new Text(text));
		}

		@Override
		public Visitor<RuntimeException> visitFootnote() throws RuntimeException {
			return addAndVisit(new Footnote());
		}

		@Override
		public Visitor<RuntimeException> visitCrossReference(String bookAbbr, BookID book, int firstChapter, String firstVerse, int lastChapter, String lastVerse) throws RuntimeException {
			return addAndVisit(new CrossReference(bookAbbr, book, firstChapter, firstVerse, lastChapter, lastVerse));
		}

		@Override
		public Visitor<RuntimeException> visitFormattingInstruction(FormattingInstructionKind kind) throws RuntimeException {
			return addAndVisit(new FormattingInstruction(kind));
		}

		@Override
		public Visitor<RuntimeException> visitCSSFormatting(String css) throws RuntimeException {
			return addAndVisit(new CSSFormatting(css));
		}

		@Override
		public void visitVerseSeparator() throws RuntimeException {
			target.elements.add(new VerseSeparator());
		}

		@Override
		public void visitLineBreak(LineBreakKind kind) throws RuntimeException {
			target.elements.add(new LineBreak(kind));
		}

		@Override
		public Visitor<RuntimeException> visitGrammarInformation(int[] strongs, String[] rmac, int[] sourceIndices) {
			return addAndVisit(new GrammarInformation(strongs, rmac, sourceIndices));
		}

		@Override
		public Visitor<RuntimeException> visitDictionaryEntry(String dictionary, String entry) throws RuntimeException {
			return addAndVisit(new DictionaryEntry(dictionary, entry));
		}

		@Override
		public void visitRawHTML(RawHTMLMode mode, String raw) throws RuntimeException {
			target.elements.add(new RawHTML(mode, raw));
		}

		@Override
		public Visitor<RuntimeException> visitVariationText(String[] variations) throws RuntimeException {
			return addAndVisit(new VariationText(variations));
		}

		@Override
		public Visitor<RuntimeException> visitExtraAttribute(ExtraAttributePriority prio, String category, String key, String value) throws RuntimeException {
			return addAndVisit(new ExtraAttribute(prio, category, key, value));
		}

		@Override
		public boolean visitEnd() throws RuntimeException {
			return false;
		}

		private Visitor<RuntimeException> addAndVisit(FormattedTextElement elem) {
			target.elements.add(elem);
			return new AppendVisitor(elem);
		}
	}

	private static class ValidatingVisitor implements Visitor<RuntimeException> {

		private final Bible bible;
		private final List<String> danglingReferences;
		private final ValidationContext context;

		private int lastHeadlineDepth = 0;
		private boolean leadingWhitespaceAllowed = false;
		private boolean trailingWhitespaceFound = false;
		private boolean isEmpty = true;

		private ValidatingVisitor(Bible bible, List<String> danglingReferences, ValidationContext context) {
			this.bible = bible;
			this.danglingReferences = danglingReferences;
			this.context = context;
		}

		@Override
		public int visitElementTypes(String elementTypes) throws RuntimeException {
			return 0;
		}

		@Override
		public void visitVerseSeparator() throws RuntimeException {
			if (context != ValidationContext.VERSE)
				throw new IllegalStateException("Verse separators are only allowed in verses!");
			visitInlineElement();
		}

		@Override
		public void visitText(String text) throws RuntimeException {
			isEmpty = false;
			lastHeadlineDepth = 0;
			if (text.startsWith(" ")) {
				if (trailingWhitespaceFound)
					throw new IllegalStateException("Whitespace adjacent to whitespace found");
				if (!leadingWhitespaceAllowed)
					throw new IllegalStateException("No whitespace allowed at beginning or after line breaks or headlines");
			}
			trailingWhitespaceFound = text.endsWith(" ");
		}

		@Override
		public void visitStart() {
		}

		@Override
		public void visitLineBreak(LineBreakKind kind) throws RuntimeException {
			if (trailingWhitespaceFound)
				throw new IllegalStateException("No whitespace allowed before line breaks");
			if (context.ordinal() >= ValidationContext.HEADLINE.ordinal() && context != ValidationContext.FOOTNOTE)
				throw new IllegalStateException("Line breaks only allowed in block context or footnotes");
			leadingWhitespaceAllowed = false;
			isEmpty = false;
			lastHeadlineDepth = 0;
		}

		@Override
		public Visitor<RuntimeException> visitHeadline(int depth) {
			if (context.ordinal() >= ValidationContext.HEADLINE.ordinal())
				throw new IllegalArgumentException("Invalid nested headline");
			if (depth <= lastHeadlineDepth)
				throw new IllegalStateException("Invalid headline depth order");
			if (trailingWhitespaceFound)
				throw new IllegalStateException("No whitespace allowed before headlines");
			leadingWhitespaceAllowed = false;
			lastHeadlineDepth = depth == 9 ? 8 : depth;
			isEmpty = false;
			return new ValidatingVisitor(bible, danglingReferences, ValidationContext.HEADLINE);
		}

		@Override
		public Visitor<RuntimeException> visitFormattingInstruction(FormattingInstructionKind kind) throws RuntimeException {
			visitInlineElement();
			return new ValidatingVisitor(bible, danglingReferences, context);
		}

		@Override
		public Visitor<RuntimeException> visitCSSFormatting(String css) throws RuntimeException {
			visitInlineElement();
			return new ValidatingVisitor(bible, danglingReferences, context);
		}

		@Override
		public Visitor<RuntimeException> visitFootnote() throws RuntimeException {
			if (context.ordinal() >= ValidationContext.FOOTNOTE.ordinal())
				throw new IllegalArgumentException("Invalid nested footnote");
			visitInlineElement();
			return new ValidatingVisitor(bible, danglingReferences, ValidationContext.FOOTNOTE);
		}

		@Override
		public Visitor<RuntimeException> visitExtraAttribute(ExtraAttributePriority prio, String category, String key, String value) throws RuntimeException {
			isEmpty = false;
			if (prio == ExtraAttributePriority.KEEP_CONTENT) {
				visitInlineElement();
				return new ValidatingVisitor(bible, danglingReferences, context);
			} else if (prio == ExtraAttributePriority.ERROR) {
				// no idea; therefore be as lax as possible
				trailingWhitespaceFound = false;
				leadingWhitespaceAllowed = true;
				lastHeadlineDepth = 0;
			}
			return null;
		}

		@Override
		public void visitRawHTML(RawHTMLMode mode, String raw) throws RuntimeException {
			visitInlineElement();
		}

		@Override
		public Visitor<RuntimeException> visitVariationText(String[] variations) throws RuntimeException {
			visitInlineElement();
			return new ValidatingVisitor(bible, danglingReferences, context);
		}

		@Override
		public Visitor<RuntimeException> visitGrammarInformation(int[] strongs, String[] rmac, int[] sourceIndices) {
			visitInlineElement();
			return new ValidatingVisitor(bible, danglingReferences, context);
		}

		@Override
		public Visitor<RuntimeException> visitDictionaryEntry(String dictionary, String entry) throws RuntimeException {
			visitInlineElement();
			return new ValidatingVisitor(bible, danglingReferences, context);
		}

		@Override
		public Visitor<RuntimeException> visitCrossReference(String bookAbbr, BookID bookID, int firstChapter, String firstVerse, int lastChapter, String lastVerse) throws RuntimeException {
			if (context.ordinal() >= ValidationContext.XREF.ordinal())
				throw new IllegalArgumentException("Invalid nested cross reference");
			if (context.ordinal() < ValidationContext.FOOTNOTE.ordinal())
				throw new IllegalArgumentException("cross references may only appear inside footnotes");
			visitInlineElement();
			Book book = bible.getBook(bookAbbr, bookID);
			int firstIndex = book == null || book.getChapters().size() < firstChapter ? -1 : book.getChapters().get(firstChapter - 1).getVerseIndex(firstVerse);
			int lastIndex = book == null || book.getChapters().size() < lastChapter ? -1 : book.getChapters().get(lastChapter - 1).getVerseIndex(lastVerse);
			if (firstIndex == -1 && danglingReferences != null) {
				danglingReferences.add(bookAbbr + "(" + bookID.getOsisID() + ") " + firstChapter + ":" + firstVerse);
			}
			if (lastIndex == -1 && danglingReferences != null) {
				danglingReferences.add(bookAbbr + "(" + bookID.getOsisID() + ") " + lastChapter + ":" + lastVerse);
			}
			if (firstIndex != -1 && lastIndex != -1 && firstChapter == lastChapter) {
				if (firstIndex > lastIndex)
					throw new IllegalStateException("First xref verse is after last xref verse");
			}
			return new ValidatingVisitor(bible, danglingReferences, ValidationContext.XREF);
		}

		@Override
		public boolean visitEnd() throws RuntimeException {
			if (trailingWhitespaceFound)
				throw new IllegalStateException("No whitespace allowed at end of element");
			if (isEmpty)
				throw new IllegalStateException("Element is empty");
			return false;
		}

		private void visitInlineElement() {
			isEmpty = false;
			leadingWhitespaceAllowed = true;
			trailingWhitespaceFound = false;
			lastHeadlineDepth = 0;
		}
	}

	private static class ElementTypeVisitor implements Visitor<RuntimeException> {

		private final StringBuilder sb;
		private final int depth;
		private final String suffix;

		public ElementTypeVisitor(StringBuilder sb, int depth, String suffix) {
			this.sb = sb;
			this.depth = depth;
			this.suffix = suffix;
		}

		@Override
		public int visitElementTypes(String elementTypes) throws RuntimeException {
			return 0;
		}

		@Override
		public Visitor<RuntimeException> visitHeadline(int depth) {
			return visitNestedType('h');
		}

		@Override
		public void visitStart() {
		}

		@Override
		public void visitText(String text) throws RuntimeException {
			sb.append('t');
		}

		@Override
		public Visitor<RuntimeException> visitFootnote() throws RuntimeException {
			return visitNestedType('f');
		}

		@Override
		public Visitor<RuntimeException> visitCrossReference(String bookAbbr, BookID book, int firstChapter, String firstVerse, int lastChapter, String lastVerse) throws RuntimeException {
			return visitNestedType('x');
		}

		@Override
		public Visitor<RuntimeException> visitFormattingInstruction(FormattingInstructionKind kind) throws RuntimeException {
			return visitNestedType('F');
		}

		@Override
		public Visitor<RuntimeException> visitCSSFormatting(String css) throws RuntimeException {
			return visitNestedType('c');
		}

		@Override
		public void visitVerseSeparator() throws RuntimeException {
			sb.append("/");
		}

		@Override
		public void visitLineBreak(LineBreakKind kind) throws RuntimeException {
			sb.append('b');
		}

		@Override
		public Visitor<RuntimeException> visitGrammarInformation(int[] strongs, String[] rmac, int[] sourceIndices) {
			return visitNestedType('g');
		}

		@Override
		public Visitor<RuntimeException> visitDictionaryEntry(String dictionary, String entry) throws RuntimeException {
			return visitNestedType('d');
		}

		@Override
		public void visitRawHTML(RawHTMLMode mode, String raw) throws RuntimeException {
			sb.append('H');
		}

		@Override
		public Visitor<RuntimeException> visitVariationText(String[] variations) throws RuntimeException {
			return visitNestedType('o');
		}

		@Override
		public Visitor<RuntimeException> visitExtraAttribute(ExtraAttributePriority prio, String category, String key, String value) throws RuntimeException {
			return visitNestedType('X');
		}

		@Override
		public boolean visitEnd() throws RuntimeException {
			sb.append(suffix);
			return false;
		}

		private ElementTypeVisitor visitNestedType(char ch) {
			sb.append(ch);
			if (depth > 1) {
				sb.append('<');
				return new ElementTypeVisitor(sb, depth - 1, ">");
			} else {
				return null;
			}
		}
	}

	private static enum ValidationContext {
		VERSE,
		NORMAL_TEXT,
		HEADLINE,
		FOOTNOTE,
		XREF,
	}
}