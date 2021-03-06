/*
 * Copyright 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.passes;

import static com.google.common.base.Ascii.toLowerCase;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Multimaps.asMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Sets;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.ErrorReporter.Checkpoint;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.CallParamContentNode;
import com.google.template.soy.soytree.CallParamValueNode;
import com.google.template.soy.soytree.CssNode;
import com.google.template.soy.soytree.DebuggerNode;
import com.google.template.soy.soytree.ForNode;
import com.google.template.soy.soytree.ForeachNode;
import com.google.template.soy.soytree.ForeachNonemptyNode;
import com.google.template.soy.soytree.HtmlAttributeNode;
import com.google.template.soy.soytree.HtmlAttributeValueNode;
import com.google.template.soy.soytree.HtmlAttributeValueNode.Quotes;
import com.google.template.soy.soytree.HtmlCloseTagNode;
import com.google.template.soy.soytree.HtmlOpenTagNode;
import com.google.template.soy.soytree.IfCondNode;
import com.google.template.soy.soytree.IfNode;
import com.google.template.soy.soytree.LetContentNode;
import com.google.template.soy.soytree.LetValueNode;
import com.google.template.soy.soytree.LogNode;
import com.google.template.soy.soytree.MsgFallbackGroupNode;
import com.google.template.soy.soytree.MsgHtmlTagNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.RawTextNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.BlockNode;
import com.google.template.soy.soytree.SoyNode.Kind;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SoyNode.StandaloneNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.soytree.SwitchCaseNode;
import com.google.template.soy.soytree.SwitchNode;
import com.google.template.soy.soytree.TagName;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.XidNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Rewrites templates and blocks of {@code kind="html"} or {@code kind="attributes"} to contain
 * {@link HtmlOpenTagNode}, {@link HtmlCloseTagNode}, {@link HtmlAttributeNode}, and {@link
 * HtmlAttributeValueNode}.
 *
 * <p>The strategy is to parse the raw text nodes for html tokens and then trigger AST rewriting
 * based on what we find.
 *
 * <p>An obvious question upon reading this should be "Why isn't this implemented in the grammar?"
 * the answer is that soy has some language features that interfere with writing such a grammar.
 *
 * <ul>
 *   <li>Soy has special commands for manipulating text, notably: {@code {nil}, {sp}, {\n}} cause
 *       difficulties when writing grammar productions for html elements. This would be manageable
 *       by preventing the use of these commands within html tags (though some of them such as
 *       {@code {sp}} are popular so there are some compatibility concerns)
 *   <li>Soy has a {@code {literal}...{/literal}} command. such commands often contain html tags so
 *       within the grammar we would need to start writing grammar production which match the
 *       contents of literal blocks. This is possible but would require duplicating all the lexical
 *       states for literals. (Also we would need to deal with tags split across literal blocks e.g.
 *       {@code <div{literal} a="foo">{/literal}}).
 *   <li>Transitioning between lexical states after seeing a {@code <script>} tag or after parsing a
 *       {@code kind="html"} attributes is complex (And 'semantic lexical transitions' are not
 *       recommended).
 * </ul>
 *
 * <p>On the other hand by implementing this here, a lot of these issues go away since all the text
 * has already been processed. Of course this doesn't mean it is easy since we need to implement our
 * own parser and state tracking system that is normally handled by the javacc grammar.
 *
 * <p>This class tries to be faithful to the <a
 * href="https://www.w3.org/TR/html5/syntax.html#syntax">Html syntax standard</a>. Though we do not
 * attempt to implement the contextual element model, and matching tags is handled by a different
 * pass, the {@link StrictHtmlValidationPass}.
 *
 * <p>TODO(lukes): there are some missing features:
 *
 * <ul>
 *   <li>Remove the parsing of {@link MsgHtmlTagNode} from the parser and move it here
 * </ul>
 */
@VisibleForTesting
public final class HtmlRewritePass extends CompilerFilePass {

  /**
   * If set to true, causes every state transition to be logged to stderr. Useful when debugging.
   */
  private static final boolean DEBUG = false;

  private static final SoyErrorKind BLOCK_CHANGES_CONTEXT =
      SoyErrorKind.of("{0} changes context from ''{1}'' to ''{2}''.{3}");

  private static final SoyErrorKind BLOCK_ENDS_IN_INVALID_STATE =
      SoyErrorKind.of("''{0}'' block ends in an invalid state ''{1}''");

  private static final SoyErrorKind BLOCK_TRANSITION_DISALLOWED =
      SoyErrorKind.of("{0} started in ''{1}'', cannot create a {2}.");

  private static final SoyErrorKind
      CONDITIONAL_BLOCK_ISNT_GUARANTEED_TO_PRODUCE_ONE_ATTRIBUTE_VALUE =
          SoyErrorKind.of(
              "expected exactly one attribute value, the {0} isn''t guaranteed to produce exactly "
                  + "one");

  private static final SoyErrorKind EXPECTED_ATTRIBUTE_VALUE =
      SoyErrorKind.of("expected an attribute value");

  private static final SoyErrorKind EXPECTED_WS_EQ_OR_CLOSE_AFTER_ATTRIBUTE_NAME =
      SoyErrorKind.of("expected whitespace, ''='' or tag close after an attribute name");

  private static final SoyErrorKind EXPECTED_WS_OR_CLOSE_AFTER_TAG_OR_ATTRIBUTE =
      SoyErrorKind.of("expected whitespace or tag close after a tag name or attribute");

  private static final SoyErrorKind FOUND_END_OF_ATTRIBUTE_STARTED_IN_ANOTHER_BLOCK =
      SoyErrorKind.of(
          "found the end of an html attribute that was started in another block. Html attributes "
              + "should be opened and closed in the same block");

  private static final SoyErrorKind FOUND_END_TAG_STARTED_IN_ANOTHER_BLOCK =
      SoyErrorKind.of(
          "found the end of a tag that was started in another block. Html tags should be opened "
              + "and closed in the same block");
  private static final SoyErrorKind FOUND_EQ_WITH_ATTRIBUTE_IN_ANOTHER_BLOCK =
      SoyErrorKind.of("found an ''='' character in a different block than the attribute name.");

  private static final SoyErrorKind GENERIC_UNEXPECTED_CHAR =
      SoyErrorKind.of("unexpected character, expected ''{0}'' instead");

  private static final SoyErrorKind ILLEGAL_HTML_ATTRIBUTE_CHARACTER =
      SoyErrorKind.of("illegal unquoted attribute value character");

  private static final SoyErrorKind INVALID_IDENTIFIER =
      SoyErrorKind.of("invalid html identifier, ''{0}'' is an illegal character");

  private static final SoyErrorKind INVALID_LOCATION_FOR_CONTROL_FLOW =
      SoyErrorKind.of("invalid location for a ''{0}'' node, {1}");

  private static final SoyErrorKind INVALID_LOCATION_FOR_NONPRINTABLE =
      SoyErrorKind.of("invalid location for a non-printable node: {0}");

  private static final SoyErrorKind INVALID_TAG_NAME =
      SoyErrorKind.of(
          "tag names may only be raw text or print nodes, consider extracting a '''{'let...'' "
              + "variable");

  private static final SoyErrorKind SELF_CLOSING_CLOSE_TAG =
      SoyErrorKind.of("close tags should not be self closing");

  private static final SoyErrorKind UNEXPECTED_CLOSE_TAG_CONTENT =
      SoyErrorKind.of("unexpected close tag content, only whitespace is allowed in close tags");

  private static final SoyErrorKind UNEXPECTED_WS_AFTER_LT =
      SoyErrorKind.of("unexpected whitespace after ''<'', did you mean ''&lt;''?");

  /** Represents features of the parser states. */
  private enum StateFeature {
    /** Means the state is part of an html 'tag' of a node (but not, inside an attribute value). */
    TAG,
    INVALID_END_STATE_FOR_BLOCK;
  }

  /**
   * Represents the contexutal state of the parser.
   *
   * <p>NOTE: {@link #reconcile(State)} depends on the ordering. So don't change the order without
   * also inspecting {@link #reconcile(State)}.
   */
  private enum State {
    NONE,
    PCDATA,
    RCDATA_SCRIPT,
    RCDATA_TEXTAREA,
    RCDATA_TITLE,
    RCDATA_STYLE,
    HTML_COMMENT,
    CDATA,
    /**
     * <!doctype, <!element, or <?xml> these work like normal tags but don't require attribute
     * values to be matched with attribute names
     */
    XML_DECLARATION,
    SINGLE_QUOTED_XML_ATTRIBUTE_VALUE,
    DOUBLE_QUOTED_XML_ATTRIBUTE_VALUE,
    HTML_TAG_NAME,
    /**
     * This state is weird - it is for <code>
     *   <pre>foo ="bar"
     *           ^
     *   </pre>
     * </code>
     *
     * <p>The state right after the attribute name. This is normally not useful (we could transition
     * right to BEFORE_ATTRIBUTE_VALUE by looking ahead for an '=' character, but we need it so we
     * can have dynamic attribute names. e.g. {xid foo}=bar.
     */
    AFTER_ATTRIBUTE_NAME(StateFeature.TAG),
    BEFORE_ATTRIBUTE_VALUE(StateFeature.INVALID_END_STATE_FOR_BLOCK),
    SINGLE_QUOTED_ATTRIBUTE_VALUE,
    DOUBLE_QUOTED_ATTRIBUTE_VALUE,
    UNQUOTED_ATTRIBUTE_VALUE,
    AFTER_TAG_NAME_OR_ATTRIBUTE(StateFeature.TAG),
    BEFORE_ATTRIBUTE_NAME(StateFeature.TAG),
    ;

    /** Gets the {@link State} for the given kind. */
    static State fromKind(@Nullable ContentKind kind) {
      if (kind == null) {
        return NONE;
      }
      switch (kind) {
        case ATTRIBUTES:
          return BEFORE_ATTRIBUTE_NAME;
        case HTML:
          return PCDATA;
          // You might be thinking that some of these should be RCDATA_STYLE or RCDATA_SCRIPT, but
          // that wouldn't be accurate since rcdata is specific to the context of js on an html page
          // in a script tag.  General js has different limitations and the autoescaper knows how to
          // escape js into rcdata_style
        case CSS:
        case JS:
        case TEXT:
        case TRUSTED_RESOURCE_URI:
        case URI:
          return NONE;
      }
      throw new AssertionError("unhandled kind: " + kind);
    }

    private final ImmutableSet<StateFeature> stateTypes;

    State(StateFeature... stateTypes) {
      EnumSet<StateFeature> set = EnumSet.noneOf(StateFeature.class);
      Collections.addAll(set, stateTypes);
      this.stateTypes = Sets.immutableEnumSet(set);
    }

    /**
     * Given 2 states, return a state that is compatible with both of them. This is useful for
     * calculating states when 2 branches of a conditional don't end in the same state. Returns
     * {@code null} if no such state exists.
     */
    @Nullable
    State reconcile(State that) {
      checkNotNull(that);
      if (that == this) {
        return this;
      }
      if (this.compareTo(that) > 0) {
        return that.reconcile(this);
      }
      // the order of comparisons here depends on the compareTo above to ensure 'this < that'
      if (this == BEFORE_ATTRIBUTE_VALUE
          && (that == UNQUOTED_ATTRIBUTE_VALUE
              || that == AFTER_TAG_NAME_OR_ATTRIBUTE
              || that == BEFORE_ATTRIBUTE_NAME)) {
        // These aren't exactly compatible, but rather are an allowed transition because
        // 1. before an unquoted attribute value and in an unquoted attribute value are not that
        //   different
        // 2. a complete attribute value is a reasonable thing to constitute a block.  This enables
        //    code like class={if $foo}"bar"{else}"baz"{/if}
        //    and it depends on additional support in the handling of control flow nodes.
        return that;
      }
      if (isTagState() && that.isTagState()) {
        return AFTER_TAG_NAME_OR_ATTRIBUTE;
      }
      switch (this) {
        case NONE:
        case PCDATA:
        case RCDATA_STYLE:
        case RCDATA_TITLE:
        case RCDATA_SCRIPT:
        case RCDATA_TEXTAREA:
        case DOUBLE_QUOTED_ATTRIBUTE_VALUE:
        case SINGLE_QUOTED_ATTRIBUTE_VALUE:
        case BEFORE_ATTRIBUTE_VALUE:
        case HTML_COMMENT:
        case HTML_TAG_NAME:
        case XML_DECLARATION:
        case CDATA:
        case DOUBLE_QUOTED_XML_ATTRIBUTE_VALUE:
        case SINGLE_QUOTED_XML_ATTRIBUTE_VALUE:
        case AFTER_ATTRIBUTE_NAME:
        case UNQUOTED_ATTRIBUTE_VALUE:
        case BEFORE_ATTRIBUTE_NAME:
        case AFTER_TAG_NAME_OR_ATTRIBUTE:
          // these all require exact matches
          return null;
      }
      throw new AssertionError("unexpected state: " + this);
    }

    boolean isTagState() {
      return stateTypes.contains(StateFeature.TAG);
    }

    boolean isInvalidForEndOfBlock() {
      return stateTypes.contains(StateFeature.INVALID_END_STATE_FOR_BLOCK);
    }

    @Override
    public String toString() {
      return Ascii.toLowerCase(name().replace('_', ' '));
    }
  }

  private final ErrorReporter errorReporter;
  private final boolean enabled;

  public HtmlRewritePass(ImmutableList<String> experimentalFeatures, ErrorReporter errorReporter) {
    // TODO(lukes): this is currently conditionally enabled for stricthtml to enable testing.
    // Turn it on unconditionally.
    this.enabled = experimentalFeatures.contains("stricthtml");
    this.errorReporter = errorReporter;
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    if (enabled) {
      new Visitor(nodeIdGen, file.getFilePath(), errorReporter).exec(file);
    } else {
      // otherwise, run on a copy of the node.
      // this will cause all of our edits to be discarded
      new Visitor(nodeIdGen, file.getFilePath(), errorReporter).exec(SoyTreeUtils.cloneNode(file));
    }
  }

  private static final class Visitor extends AbstractSoyNodeVisitor<Void> {
    
    /**
     * Matches a subset of the inverse of {@link #TAG_IDENTIFIER_MATCHER} which indicate a parsing
     * error instead of a delimiter.
     */
    static final CharMatcher INVALID_IDENTIFIER_CHAR_MATCHER =
        CharMatcher.anyOf("\0'\"").precomputed();

    /**
     * Matches raw text in a tag that isn't a special character or whitespace.
     *
     * <p>This is based on the attribute parsing spec:
     * https://www.w3.org/TR/html5/syntax.html#attributes-0
     */
    static final CharMatcher TAG_IDENTIFIER_MATCHER =
        // delimiter charachters
        CharMatcher.whitespace()
            .or(CharMatcher.anyOf(">=/"))
            .or(INVALID_IDENTIFIER_CHAR_MATCHER)
            .or(
                new CharMatcher() {
                  @Override
                  public boolean matches(char c) {
                    return Character.getType(c) == Character.CONTROL;
                  }
                })
            .negate()
            .precomputed();

    // see https://www.w3.org/TR/html5/syntax.html#attributes-0
    /** Matches all the characters that are allowed to appear in an unquoted attribute value. */
    static final CharMatcher UNQUOTED_ATTRIBUTE_VALUE_MATCHER =
        CharMatcher.whitespace().or(CharMatcher.anyOf("<>='\"`")).negate().precomputed();
    /** Matches the delimiter characters of an unquoted attribute value. */
    static final CharMatcher UNQUOTED_ATTRIBUTE_VALUE_DELIMITER =
        CharMatcher.whitespace().or(CharMatcher.is('>')).precomputed();

    static final CharMatcher NOT_DOUBLE_QUOTE = CharMatcher.isNot('"').precomputed();
    static final CharMatcher NOT_SINGLE_QUOTE = CharMatcher.isNot('\'').precomputed();
    static final CharMatcher NOT_LT = CharMatcher.isNot('<').precomputed();
    static final CharMatcher NOT_RSQUARE_BRACE = CharMatcher.isNot(']').precomputed();
    static final CharMatcher NOT_HYPHEN = CharMatcher.isNot('-').precomputed();
    static final CharMatcher XML_DECLARATION_NON_DELIMITERS =
        CharMatcher.noneOf(">\"'").precomputed();

    final IdGenerator nodeIdGen;
    final String filePath;
    final AstEdits edits = new AstEdits();
    final ErrorReporter errorReporter;

    // RawText handling fields.
    RawTextNode currentRawTextNode;
    String currentRawText;
    int currentRawTextOffset;
    int currentRawTextIndex;

    ParsingContext context;

    Visitor(IdGenerator nodeIdGen, String filePath, ErrorReporter errorReporter) {
      this.nodeIdGen = nodeIdGen;
      this.filePath = filePath;
      this.errorReporter = errorReporter;
    }

    /**
     * For RawText we need to examine every character.
     *
     * <p>We track an index and an offset into the current RawTextNode (currentRawTextIndex and
     * currentRawTextOffset respectively). 'advance' methods move the index and 'consume' methods
     * optionally move the index and always set the offset == index. (they 'consume' the text
     * between the offset and the index.
     *
     * <p>handle* methods will 'handle the current state'
     *
     * <ul>
     *   <li>Precondition : They are in the given state and not at the end of the input
     *   <li> Postcondition: They have either advanced the current index or changed states
     *       (generally both)
     * </ul>
     *
     * <p>NOTE: a consequence of these conditions is that they are only guaranteed to be able to
     * consume a single character.
     *
     * <p>At the end of visiting a raw text node, all the input will be consumed.
     */
    @Override
    protected void visitRawTextNode(RawTextNode node) {
      currentRawTextNode = node;
      currentRawText = node.getRawText();
      currentRawTextOffset = 0;
      currentRawTextIndex = 0;
      int prevStartIndex = -1;
      while (currentRawTextIndex < currentRawText.length()) {
        int startIndex = currentRawTextIndex;
        // if whitespace was trimmed prior to the current character (e.g. leading whitespace)
        // handle it.
        // However, we should only handle it once, otherwise state transitions which don't consume
        // input may cause the same joined whitespace to be handled multiple times.
        if (startIndex != prevStartIndex && currentRawTextNode.missingWhitespaceAt(startIndex)) {
          handleJoinedWhitespace(currentPoint());
        }
        prevStartIndex = startIndex;
        State startState = context.getState();
        switch (startState) {
          case NONE:
            // no replacements, no parsing, just jump to the end
            currentRawTextIndex = currentRawTextOffset = currentRawText.length();
            break;
          case PCDATA:
            handlePcData();
            break;
          case DOUBLE_QUOTED_ATTRIBUTE_VALUE:
            handleQuotedAttributeValue(true);
            break;
          case SINGLE_QUOTED_ATTRIBUTE_VALUE:
            handleQuotedAttributeValue(false);
            break;
          case BEFORE_ATTRIBUTE_VALUE:
            handleBeforeAttributeValue();
            break;
          case AFTER_TAG_NAME_OR_ATTRIBUTE:
            handleAfterTagNameOrAttribute();
            break;
          case BEFORE_ATTRIBUTE_NAME:
            handleBeforeAttributeName();
            break;
          case UNQUOTED_ATTRIBUTE_VALUE:
            handleUnquotedAttributeValue();
            break;
          case AFTER_ATTRIBUTE_NAME:
            handleAfterAttributeName();
            break;
          case HTML_TAG_NAME:
            handleHtmlTagName();
            break;
          case RCDATA_STYLE:
            handleRcData(TagName.RcDataTagName.STYLE);
            break;
          case RCDATA_TITLE:
            handleRcData(TagName.RcDataTagName.TITLE);
            break;
          case RCDATA_SCRIPT:
            handleRcData(TagName.RcDataTagName.SCRIPT);
            break;
          case RCDATA_TEXTAREA:
            handleRcData(TagName.RcDataTagName.TEXTAREA);
            break;
          case CDATA:
            handleCData();
            break;
          case HTML_COMMENT:
            handleHtmlComment();
            break;
          case XML_DECLARATION:
            handleXmlDeclaration();
            break;
          case DOUBLE_QUOTED_XML_ATTRIBUTE_VALUE:
            handleXmlAttributeQuoted(true);
            break;
          case SINGLE_QUOTED_XML_ATTRIBUTE_VALUE:
            handleXmlAttributeQuoted(false);
            break;
          default:
            throw new UnsupportedOperationException("cant yet handle: " + startState);
        }
        if (context.getState() == startState && startIndex == currentRawTextIndex) {
          // sanity! make sure we are making progress.  Calling handle* should ensure that we
          // advance at least one character or change states.  (generally both will happen, but
          // after producing an error we may only switch states).
          throw new IllegalStateException(
              "failed to make progress in state: "
                  + startState.name()
                  + " at "
                  + currentLocation());
        }
        if (currentRawTextOffset > currentRawTextIndex) {
          throw new IllegalStateException(
              "offset is greater than index! offset: "
                  + currentRawTextOffset
                  + " index: "
                  + currentRawTextIndex);
        }
      }
      if (currentRawTextIndex != currentRawText.length()) {
        throw new AssertionError("failed to visit all of the raw text");
      }
      if (currentRawTextOffset < currentRawTextIndex && currentRawTextOffset != 0) {
        // This handles all the states that just advance to the end without consuming
        // TODO(lukes): maybe this should be an error and all such states will need to consume?
        RawTextNode suffix = consumeAsRawText();
        edits.replace(node, suffix);
      }
      // handle trailing joined whitespace.
      if (currentRawTextNode.missingWhitespaceAt(currentRawText.length())) {
        handleJoinedWhitespace(currentRawTextNode.getSourceLocation().getEndPoint());
      }
    }

    /** Called to handle whitespace that was completely removed from a raw text node. */
    void handleJoinedWhitespace(SourceLocation.Point point) {
      switch (context.getState()) {
        case UNQUOTED_ATTRIBUTE_VALUE:
          context.createUnquotedAttributeValue(point);
          // fall-through
        case AFTER_TAG_NAME_OR_ATTRIBUTE:
          context.setState(State.BEFORE_ATTRIBUTE_NAME, point);
          return;
        case AFTER_ATTRIBUTE_NAME:
          int currentChar = currentChar();
          // We are at the end of the raw text, or it is some character other than whitespace or an
          // equals sign -> BEFORE_ATTRIBUTE_NAME
          if (currentChar == -1
              || (!CharMatcher.whitespace().matches((char) currentChar)
                  && '=' != (char) currentChar)) {
            context.setState(State.BEFORE_ATTRIBUTE_NAME, point);
            return;
          }
          // fall through
        case BEFORE_ATTRIBUTE_VALUE:
        case BEFORE_ATTRIBUTE_NAME:
        case DOUBLE_QUOTED_XML_ATTRIBUTE_VALUE:
        case HTML_TAG_NAME:
        case HTML_COMMENT:
        case CDATA:
        case NONE:
        case SINGLE_QUOTED_ATTRIBUTE_VALUE:
        case DOUBLE_QUOTED_ATTRIBUTE_VALUE:
          // TODO(lukes): line joining and whitespace removal can happen within quoted attribute
          // values... we could take steps to undo it... should we?
        case PCDATA:
        case RCDATA_SCRIPT:
        case RCDATA_STYLE:
        case RCDATA_TEXTAREA:
        case RCDATA_TITLE:
        case SINGLE_QUOTED_XML_ATTRIBUTE_VALUE:
        case XML_DECLARATION:
          // no op
          return;
      }
      throw new AssertionError();
    }

    /**
     * Handle rcdata blocks (script, style, title, textarea).
     *
     * <p>Scans for {@code </tagName} and if it finds it, enters {@link State#PCDATA}.
     */
    void handleRcData(TagName.RcDataTagName tagName) {
      boolean foundLt = advanceWhileMatches(NOT_LT);
      if (foundLt) {
        if (matchPrefixIgnoreCase("</" + tagName, false /* don't advance */)) {
          // we don't advance.  instead we just switch to pcdata and since the current index is on
          // a '<' character, this will cause us to parse a close tag, which is what we want
          context.setState(State.PCDATA, currentPoint());
        } else {
          advance();
        }
      }
    }

    /**
     * Handle cdata.
     *
     * <p>Scans for {@code ]]>} and if it finds it, enters {@link State#PCDATA}.
     */
    void handleCData() {
      boolean foundBrace = advanceWhileMatches(NOT_RSQUARE_BRACE);
      if (foundBrace) {
        if (matchPrefix("]]>", true)) {
          context.setState(State.PCDATA, currentPointOrEnd());
        } else {
          advance();
        }
      }
    }

    /**
     * Handle html comments.
     *
     * <p>Scans for {@code -->} and if it finds it, enters {@link State#PCDATA}.
     */
    void handleHtmlComment() {
      boolean foundHyphen = advanceWhileMatches(NOT_HYPHEN);
      if (foundHyphen) {
        if (matchPrefix("-->", true)) {
          context.setState(State.PCDATA, currentPointOrEnd());
        } else {
          advance();
        }
      }
    }

    /**
     * Handle {@link State#XML_DECLARATION}.
     *
     * <p>This is for things like {@code <!DOCTYPE HTML PUBLIC
     * "http://www.w3.org/TR/html4/strict.dtd">}. . We are looking for the end or a quoted
     * 'attribute'.
     */
    void handleXmlDeclaration() {
      boolean foundDelimiter = advanceWhileMatches(XML_DECLARATION_NON_DELIMITERS);
      if (foundDelimiter) {
        int c = currentChar();
        SourceLocation.Point currentPoint = currentPoint();
        advance();
        if (c == '"') {
          context.setState(State.DOUBLE_QUOTED_XML_ATTRIBUTE_VALUE, currentPoint);
        } else if (c == '\'') {
          context.setState(State.SINGLE_QUOTED_XML_ATTRIBUTE_VALUE, currentPoint);
        } else if (c == '>') {
          context.setState(State.PCDATA, currentPoint);
        } else {
          throw new AssertionError("unexpected character: " + c);
        }
      }
    }

    /** Handle an xml quoted attribute. We just scan for the appropriate quote character. */
    void handleXmlAttributeQuoted(boolean doubleQuoted) {
      boolean foundQuote = advanceWhileMatches(doubleQuoted ? NOT_DOUBLE_QUOTE : NOT_SINGLE_QUOTE);
      if (foundQuote) {
        advance();
        context.setState(State.XML_DECLARATION, currentPoint());
      }
    }

    /**
     * Handle pcdata.
     *
     * <p>Scan for {@code <} if we find it we know we are at the start of a tag, a comment, cdata or
     * an xml declaration. Create a text node for everything up to the beginning of the tag-like
     * thing.
     */
    void handlePcData() {
      boolean foundLt = advanceWhileMatches(NOT_LT);
      RawTextNode node = consumeAsRawText();
      if (node != null) {
        edits.replace(currentRawTextNode, node);
      }
      // if there is more, then we stopped advancing because we hit a '<' character
      if (foundLt) {
        SourceLocation.Point ltPoint = currentPoint();
        if (matchPrefix("<!--", true)) {
          context.setState(State.HTML_COMMENT, ltPoint);
        } else if (matchPrefixIgnoreCase("<![cdata", true)) {
          context.setState(State.CDATA, ltPoint);
        } else if (matchPrefix("<!", true) || matchPrefix("<?", true)) {
          context.setState(State.XML_DECLARATION, ltPoint);
        } else {
          // if it isn't either of those special cases, enter a tag
          boolean isCloseTag = matchPrefix("</", false);
          context.startTag(currentRawTextNode, isCloseTag, currentPoint());
          advance(); // go past the '<'
          if (isCloseTag) {
            advance(); // go past the '/'
          }
          consume(); // set the offset to the current index
          context.setState(State.HTML_TAG_NAME, ltPoint);
        }
      }
    }

    /**
     * Handle parsing an html tag name.
     *
     * <p>Look for an html identifier and transition to AFTER_ATTRIBUTE_OR_TAG_NAME.
     */
    void handleHtmlTagName() {
      // special case error message handle whitespace following a <, report an error and assume it
      // wasn't the start of a tag.
      if (consumeWhitespace()) {
        errorReporter.report(context.tagStartLocation(), UNEXPECTED_WS_AFTER_LT);
        context.reset();
        context.setState(State.PCDATA, currentPointOrEnd());
        return;
      }
      RawTextNode node = consumeHtmlIdentifier();
      if (node == null) {
        // we must have immediately come across a delimiter like <, =, >, ' or "
        errorReporter.report(currentLocation(), GENERIC_UNEXPECTED_CHAR, "an html tag");
        context.setTagName(
            new TagName(new RawTextNode(nodeIdGen.genId(), "$parse-error$", currentLocation())));
      } else {
        context.setTagName(new TagName(node));
      }
    }

    /**
     * Handle the state immediately after an attribute or tag.
     *
     * <p>Look for either whitespace or the end of the tag.
     */
    void handleAfterTagNameOrAttribute() {
      if (consumeWhitespace()) {
        context.setState(State.BEFORE_ATTRIBUTE_NAME, currentPointOrEnd());
        return;
      }
      if (!tryCreateTagEnd()) {
        errorReporter.report(currentLocation(), EXPECTED_WS_OR_CLOSE_AFTER_TAG_OR_ATTRIBUTE);
        // transition to a new state and try to keep going, note, we don't consume the current
        // character
        context.setState(State.BEFORE_ATTRIBUTE_NAME, currentPoint());
        advance(); // move ahead
      }
    }

    /**
     * Handle the state where we are right before an attribute.
     *
     * <p>This state is kind of confusing, it just means we are in the middle of a tag and are
     * definitely after some whitespace.
     */
    void handleBeforeAttributeName() {
      if (tryCreateTagEnd()) {
        return;
      }
      if (consumeWhitespace()) {
        // if we consumed whitespace, return and keep going.
        // we don't necessarily expect whitespace, but it is ok if there is extra whitespace here
        // this can happen in the case of kind="attributes" blocks which start in this state, or
        // if raw text nodes are split strangely.
        // We have to return in case we hit the end of the raw text.
        return;
      }
      RawTextNode identifier = consumeHtmlIdentifier();
      if (identifier == null) {
        // consumeHtmlIdentifier will have already reported an error
        context.resetAttribute();
        return;
      }
      context.startAttribute(identifier);
    }

    /**
     * Handle immediately after an attribute name.
     *
     * <p>Look for an '=' sign to signal the presence of an attribute value
     */
    void handleAfterAttributeName() {
      boolean ws = consumeWhitespace();
      int current = currentChar();
      if (current == '=') {
        SourceLocation.Point equalsSignPoint = currentPoint();
        advance();
        consume(); // eat the '='
        consumeWhitespace();
        context.setEqualsSignLocation(equalsSignPoint, currentPointOrEnd());
      } else {
        // we must have seen some non '=' character (or the end of the text), it doesn't matter
        // what it is, switch to the next state.  (creation of the attribute will happen
        // automatically if it hasn't already).
        context.setState(
            ws ? State.BEFORE_ATTRIBUTE_NAME : State.AFTER_TAG_NAME_OR_ATTRIBUTE,
            currentPointOrEnd());
      }
    }

    /**
     * Handle immediately before an attribute value.
     *
     * <p>Look for a quote character to signal the beginning of a quoted attribute value or switch
     * to UNQUOTED_ATTRIBUTE_VALUE to handle that.
     */
    void handleBeforeAttributeValue() {
      // per https://www.w3.org/TR/html5/syntax.html#attributes-0
      // we are allowed an arbitrary amount of whitespace preceding an attribute value.
      boolean ws = consumeWhitespace();
      if (ws) {
        // return without changing states to handle eof conditions
        return;
      }
      int c = currentChar();
      if (c == '\'' || c == '"') {
        SourceLocation.Point quotePoint = currentPoint();
        context.startQuotedAttributeValue(
            currentRawTextNode,
            quotePoint,
            c == '"' ? State.DOUBLE_QUOTED_ATTRIBUTE_VALUE : State.SINGLE_QUOTED_ATTRIBUTE_VALUE);
        advance();
        consume();
      } else {
        context.setState(State.UNQUOTED_ATTRIBUTE_VALUE, currentPoint());
      }
    }

    /**
     * Handle unquoted attribute values.
     *
     * <p>Search for whitespace or the end of the tag as a delimiter.
     */
    void handleUnquotedAttributeValue() {
      boolean foundDelimiter = advanceWhileMatches(UNQUOTED_ATTRIBUTE_VALUE_MATCHER);
      RawTextNode node = consumeAsRawText();
      if (node != null) {
        context.addAttributeValuePart(node);
      }
      if (foundDelimiter) {
        context.createUnquotedAttributeValue(currentPoint());
        char c = (char) currentChar();
        if (!UNQUOTED_ATTRIBUTE_VALUE_DELIMITER.matches(c)) {
          errorReporter.report(currentLocation(), ILLEGAL_HTML_ATTRIBUTE_CHARACTER);
          // go past it and consume it
          advance();
          consume();
        }
      }
      // otherwise keep going, to support things like
      //  <div a=a{$p}>
      //  <div a={$p}a>
      //  <div a=a{$p}a>
    }

    /**
     * Handle a quoted attribute value.
     *
     * <p>These are easy we just look for the end quote.
     */
    void handleQuotedAttributeValue(boolean doubleQuoted) {
      boolean hasQuote = advanceWhileMatches(doubleQuoted ? NOT_DOUBLE_QUOTE : NOT_SINGLE_QUOTE);
      RawTextNode data = consumeAsRawText();
      if (data != null) {
        context.addAttributeValuePart(data);
      }
      if (hasQuote) {
        if (context.hasQuotedAttributeValueParts()) {
          context.createQuotedAttributeValue(currentRawTextNode, doubleQuoted, currentPoint());
        } else {
          errorReporter.report(currentLocation(), FOUND_END_OF_ATTRIBUTE_STARTED_IN_ANOTHER_BLOCK);
          throw new AbortParsingBlockError();
        }
        // consume the quote
        advance();
        consume();
      }
    }

    /** Attempts to finish the current tag, returns true if it did. */
    boolean tryCreateTagEnd() {
      int c = currentChar();
      if (c == '>') {
        if (context.hasTagStart()) {
          SourceLocation.Point point = currentPoint();
          context.setState(context.createTag(currentRawTextNode, false, point), point);
        } else {
          errorReporter.report(currentLocation(), FOUND_END_TAG_STARTED_IN_ANOTHER_BLOCK);
          throw new AbortParsingBlockError();
        }
        advance();
        consume();
        return true;
      } else if (matchPrefix("/>", false)) {
        // position the index on the '>' so that the end location of the tag calculated by
        // currentPoint is accurate
        advance();
        if (context.hasTagStart()) {
          SourceLocation.Point point = currentPoint();
          context.setState(context.createTag(currentRawTextNode, true, point), point);
        } else {
          errorReporter.report(currentLocation(), FOUND_END_TAG_STARTED_IN_ANOTHER_BLOCK);
          throw new AbortParsingBlockError();
        }
        // consume the rest of the '/>'
        advance();
        consume();
        return true;
      }
      return false;
    }

    /** Returns {@code true} if we haven't reached the end of the string. */
    boolean advanceWhileMatches(CharMatcher c) {
      int next = currentChar();
      while (next != -1 && c.matches((char) next)) {
        advance();
        next = currentChar();
      }
      return next != -1;
    }

    /**
     * Eats all whitespace from the input prefix. Returns {@code true} if we matched any whitespace
     * characters.
     */
    boolean consumeWhitespace() {
      int startIndex = currentRawTextIndex;
      advanceWhileMatches(CharMatcher.whitespace());
      consume();
      edits.remove(currentRawTextNode); // mark the current node for removal
      return currentRawTextIndex != startIndex;
    }

    /**
     * Scans until the next whitespace, > or />, validates that the matched text is an html
     * identifier and returns it.
     */
    @Nullable
    RawTextNode consumeHtmlIdentifier() {
      // rather than use a regex to match the prefix, we just consume all non-whitespace/non-meta
      // characters and then validate the text afterwards.
      boolean foundDelimiter = advanceWhileMatches(TAG_IDENTIFIER_MATCHER);
      RawTextNode node = consumeAsRawText();
      if (node != null) {
        if (foundDelimiter && INVALID_IDENTIFIER_CHAR_MATCHER.matches((char) currentChar())) {
          errorReporter.report(currentLocation(), INVALID_IDENTIFIER, (char) currentChar());
          // consume the bad char
          advance();
          consume();
        }
      } else {
        errorReporter.report(currentLocation(), GENERIC_UNEXPECTED_CHAR, "an html identifier");
        // consume the character
        advance();
        consume();
      }
      return node;
    }

    /**
     * Returns [{@link #currentRawTextOffset}, {@link #currentRawTextIndex}) as a RawTextNode, or
     * {@code null} if it is empty.
     */
    @Nullable
    RawTextNode consumeAsRawText() {
      if (currentRawTextIndex == currentRawTextOffset) {
        return null;
      }
      edits.remove(currentRawTextNode);
      RawTextNode node =
          currentRawTextNode.substring(
              nodeIdGen.genId(), currentRawTextOffset, currentRawTextIndex);
      consume();
      return node;
    }

    /** Returns the location of the current character. */
    SourceLocation currentLocation() {
      return currentRawTextNode.substringLocation(currentRawTextIndex, currentRawTextIndex + 1);
    }

    /** The {@code SourceLocation.Point} of the {@code currentRawTextIndex}. */
    SourceLocation.Point currentPoint() {
      return currentRawTextNode.locationOf(currentRawTextIndex);
    }

    /**
     * The {@code SourceLocation.Point} of the {@code currentRawTextIndex} or the end of the raw
     * text if we are at the end.
     */
    SourceLocation.Point currentPointOrEnd() {
      if (currentRawText.length() > currentRawTextIndex) {
        return currentPoint();
      }
      return currentRawTextNode.getSourceLocation().getEndPoint();
    }

    /** Returns the current character or {@code -1} if we are at the end of the output. */
    int currentChar() {
      if (currentRawTextIndex < currentRawText.length()) {
        return currentRawText.charAt(currentRawTextIndex);
      }
      return -1;
    }

    /** Advances the {@link #currentRawTextIndex} by {@code n} */
    void advance(int n) {
      checkArgument(n > 0);
      for (int i = 0; i < n; i++) {
        advance();
      }
    }
    /** Advances the {@link #currentRawTextIndex} by {@code 1} */
    void advance() {
      if (currentRawTextIndex >= currentRawText.length()) {
        throw new AssertionError("already advanced to the end, shouldn't advance any more");
      }
      currentRawTextIndex++;
    }

    /** Sets the {@link #currentRawTextOffset} to be equal to {@link #currentRawTextIndex}. */
    void consume() {
      currentRawTextOffset = currentRawTextIndex;
    }

    /**
     * Returns true if the beginning of the input matches the given prefix.
     *
     * @param advance if the prefix matches, advance the length of the prefix.
     */
    boolean matchPrefix(String prefix, boolean advance) {
      if (currentRawText.startsWith(prefix, currentRawTextIndex)) {
        if (advance) {
          advance(prefix.length());
        }
        return true;
      }
      return false;
    }

    /**
     * Returns true if the beginning of the input matches the given prefix ignoring ASCII case.
     *
     * @param advance if the prefix matches, advance the length of the prefix.
     */
    boolean matchPrefixIgnoreCase(String s, boolean advance) {
      if (currentRawTextIndex + s.length() <= currentRawText.length()) {
        // we use an explicit loop instead of Ascii.equalsIgnoringCase + substring to avoid the
        // allocations implied by substring
        for (int i = 0; i < s.length(); i++) {
          char c1 = s.charAt(i);
          char c2 = currentRawText.charAt(i + currentRawTextIndex);
          if (c1 != c2 && toLowerCase(c1) != toLowerCase(c2)) {
            return false;
          }
        }
        if (advance) {
          advance(s.length());
        }
        return true;
      }
      return false;
    }

    // scoped blocks, each one of these can enter/exit a new state
    @Override
    protected void visitTemplateNode(TemplateNode node) {
      // reset everything for each template
      edits.clear();
      context = null;

      Checkpoint checkPoint = errorReporter.checkpoint();
      visitScopedBlock(node.getContentKind(), node, "template");

      // we only rewrite the template if there were no new errors while parsing it
      if (!errorReporter.errorsSince(checkPoint)) {
        edits.apply();
      }
    }

    @Override
    protected void visitLetValueNode(LetValueNode node) {
      processNonPrintableNode(node);
    }

    @Override
    protected void visitLetContentNode(LetContentNode node) {
      visitScopedBlock(node.getContentKind(), node, "let");
      processNonPrintableNode(node);
    }

    @Override
    protected void visitDebuggerNode(DebuggerNode node) {
      processNonPrintableNode(node);
    }

    @Override
    protected void visitCallParamContentNode(CallParamContentNode node) {
      visitScopedBlock(node.getContentKind(), node, "param");
    }

    @Override
    protected void visitCallParamValueNode(CallParamValueNode node) {
      // do nothing
    }

    @Override
    protected void visitCallNode(CallNode node) {
      visitChildren(node);
      processPrintableNode(node);
      if (context.getState() == State.PCDATA) {
        node.setIsPcData(true);
      }
    }

    @Override
    protected void visitMsgFallbackGroupNode(MsgFallbackGroupNode node) {
      // TODO(lukes): Start parsing html tags in {msg} nodes here instead of in the parser.
      // to avoid doing it now, we don't visit children
      processPrintableNode(node);
    }

    // control flow blocks

    @Override
    protected void visitForNode(ForNode node) {
      visitControlFlowStructure(
          node,
          ImmutableList.<BlockNode>of(node),
          "for loop",
          Functions.constant("loop body"),
          false /* no guarantee that the children will execute exactly once. */,
          false /* no guarantee that the children will execute at least once. */);
    }

    @Override
    protected void visitForeachNode(ForeachNode node) {
      visitControlFlowStructure(
          node,
          node.getChildren(),
          "foreach loop",
          new Function<BlockNode, String>() {
            @Override
            public String apply(@Nullable BlockNode input) {
              if (input instanceof ForeachNonemptyNode) {
                return "loop body";
              }
              return "ifempty block";
            }
          },
          false /* no guarantee that the children will only execute once. */,
          node.hasIfEmptyBlock() /* one branch will execute if there is an ifempty block. */);
    }

    @Override
    protected void visitIfNode(final IfNode node) {
      boolean hasElse = node.hasElse();
      visitControlFlowStructure(
          node,
          node.getChildren(),
          "if",
          new Function<BlockNode, String>() {
            @Override
            public String apply(@Nullable BlockNode input) {
              if (input instanceof IfCondNode) {
                if (node.getChild(0) == input) {
                  return "if block";
                }
                return "elseif block";
              }
              return "else block";
            }
          },
          // one and only one child will execute if we have an else
          hasElse,
          hasElse);
    }

    @Override
    protected void visitSwitchNode(SwitchNode node) {
      boolean hasDefault = node.hasDefaultCase();
      visitControlFlowStructure(
          node,
          node.getChildren(),
          "switch",
          new Function<BlockNode, String>() {
            @Override
            public String apply(@Nullable BlockNode input) {
              if (input instanceof SwitchCaseNode) {
                return "case block";
              }
              return "default block";
            }
          },
          // one and only one child will execute if we have a default
          hasDefault,
          hasDefault);
    }

    @Override
    protected void visitLogNode(LogNode node) {
      // we don't need to create a new context, just set the state to NONE there is no transition
      // from NONE to anything else.
      State oldState = context.setState(State.NONE, node.getSourceLocation().getBeginPoint());
      visitChildren(node);
      context.setState(oldState, node.getSourceLocation().getEndPoint());
      processNonPrintableNode(node);
    }

    @Override
    protected void visitCssNode(CssNode node) {
      processPrintableNode(node);
    }

    @Override
    protected void visitPrintNode(PrintNode node) {
      processPrintableNode(node);
      // no need to visit children. The only children are PrintDirectiveNodes which are more like
      // expressions than soy nodes.
    }

    @Override
    protected void visitXidNode(XidNode node) {
      processPrintableNode(node);
    }

    void processNonPrintableNode(StandaloneNode node) {
      switch (context.getState()) {
        case AFTER_TAG_NAME_OR_ATTRIBUTE:
        case BEFORE_ATTRIBUTE_NAME:
        case AFTER_ATTRIBUTE_NAME:
          context.addTagChild(node);
          break;
        case BEFORE_ATTRIBUTE_VALUE:
          errorReporter.report(
              node.getSourceLocation(),
              INVALID_LOCATION_FOR_NONPRINTABLE,
              "move it before the start of the tag or after the tag name");
          break;
        case HTML_TAG_NAME:
          errorReporter.report(
              node.getSourceLocation(),
              INVALID_LOCATION_FOR_NONPRINTABLE,
              "it creates ambiguity with an unquoted attribute value");
          break;
        case UNQUOTED_ATTRIBUTE_VALUE:
        case DOUBLE_QUOTED_ATTRIBUTE_VALUE:
        case SINGLE_QUOTED_ATTRIBUTE_VALUE:
          context.addAttributeValuePart(node);
          break;
        case HTML_COMMENT:
        case NONE:
        case PCDATA:
        case RCDATA_SCRIPT:
        case RCDATA_STYLE:
        case RCDATA_TEXTAREA:
        case RCDATA_TITLE:
        case XML_DECLARATION:
        case CDATA:
        case DOUBLE_QUOTED_XML_ATTRIBUTE_VALUE:
        case SINGLE_QUOTED_XML_ATTRIBUTE_VALUE:
          // do nothing
          break;
        default:
          throw new AssertionError();
      }
    }

    /** Printable nodes are things like {xid..} and {print ..}. */
    void processPrintableNode(StandaloneNode node) {
      checkState(node.getKind() != Kind.RAW_TEXT_NODE);
      switch (context.getState()) {
        case AFTER_TAG_NAME_OR_ATTRIBUTE:
          errorReporter.report(
              node.getSourceLocation(), EXPECTED_WS_OR_CLOSE_AFTER_TAG_OR_ATTRIBUTE);
          break;

        case AFTER_ATTRIBUTE_NAME:
          errorReporter.report(
              node.getSourceLocation(), EXPECTED_WS_EQ_OR_CLOSE_AFTER_ATTRIBUTE_NAME);
          break;

        case BEFORE_ATTRIBUTE_NAME:
          context.startAttribute(node);
          break;

        case HTML_TAG_NAME:
          if (node.getKind() == Kind.PRINT_NODE) {
            context.setTagName(new TagName((PrintNode) node));
            edits.remove(node);
          } else {
            errorReporter.report(node.getSourceLocation(), INVALID_TAG_NAME);
          }
          break;

        case BEFORE_ATTRIBUTE_VALUE:
          // we didn't see a quote, so just turn this into an attribute value.
          context.setState(
              State.UNQUOTED_ATTRIBUTE_VALUE, node.getSourceLocation().getBeginPoint());
          // fall through
        case DOUBLE_QUOTED_ATTRIBUTE_VALUE:
        case SINGLE_QUOTED_ATTRIBUTE_VALUE:
        case UNQUOTED_ATTRIBUTE_VALUE:
          context.addAttributeValuePart(node);
          break;
        case HTML_COMMENT:
        case NONE:
        case PCDATA:
        case RCDATA_SCRIPT:
        case RCDATA_STYLE:
        case RCDATA_TEXTAREA:
        case RCDATA_TITLE:
        case XML_DECLARATION:
        case CDATA:
        case DOUBLE_QUOTED_XML_ATTRIBUTE_VALUE:
        case SINGLE_QUOTED_XML_ATTRIBUTE_VALUE:
          // do nothing
          break;

        default:
          throw new AssertionError("unexpected state: " + context.getState());
      }
    }

    @Override
    protected void visitSoyFileNode(SoyFileNode node) {
      visitChildren(node);
    }

    @Override
    protected void visitSoyNode(SoyNode node) {
      throw new UnsupportedOperationException(node.getKind() + " isn't supported yet");
    }

    /** Visits a block whose content is in an entirely separate content scope. */
    void visitScopedBlock(ContentKind blockKind, BlockNode parent, String name) {
      State startState = State.fromKind(blockKind);
      Checkpoint checkpoint = errorReporter.checkpoint();
      ParsingContext newCtx =
          newParsingContext(name, startState, parent.getSourceLocation().getBeginPoint());
      ParsingContext oldCtx = setContext(newCtx);
      visitBlock(startState, parent, name, checkpoint);
      setContext(oldCtx); // restore
    }

    /**
     * Visits a control flow structure like an if, switch or a loop.
     *
     * <p>The main thing this is responsible for is calculating what state to enter after the
     * control flow is complete.
     *
     * @param parent The parent node, each child will be a block representing one of the branches
     * @param children The child blocks. We don't use {@code parent.getChildren()} directly to make
     *     it possible to handle ForNodes using this method.
     * @param overallName The name, for error reporting purposes, to assign to the control flow
     *     structure
     * @param blockNamer A function to provide a name for each child block, the key is the index of
     *     the block
     * @param willExactlyOneBranchExecuteOnce Whether or not it is guaranteed that exactly one
     *     branch of the structure will execute exactly one time.
     * @param willAtLeastOneBranchExecute Whether or not it is guaranteed that at least one of the
     *     branches will execute (as opposed to no branches executing).
     */
    void visitControlFlowStructure(
        StandaloneNode parent,
        List<? extends BlockNode> children,
        String overallName,
        Function<? super BlockNode, String> blockNamer,
        boolean willExactlyOneBranchExecuteOnce,
        boolean willAtLeastOneBranchExecute) {

      // this insane case can happen with SwitchNodes.
      if (children.isEmpty()) {
        return;
      }
      State startingState = context.getState();
      State endingState = visitBranches(children, blockNamer);
      SourceLocation.Point endPoint = parent.getSourceLocation().getEndPoint();
      switch (startingState) {
        case AFTER_TAG_NAME_OR_ATTRIBUTE:
        case BEFORE_ATTRIBUTE_NAME:
        case AFTER_ATTRIBUTE_NAME:
          context.addTagChild(parent);
          // at this point we are in AFTER_TAG_NAME_OR_ATTRIBUTE, switch to whatever the branches
          // ended in, the reconcilation logic may have calculated a better state (like
          // BEFORE_ATTRIBUTE_NAME).
          context.setState(endingState, endPoint);
          break;
        case HTML_TAG_NAME:
          errorReporter.report(
              parent.getSourceLocation(),
              INVALID_LOCATION_FOR_CONTROL_FLOW,
              overallName,
              "html tag names can only be constants or print nodes");
          // give up on parsing this tag :(
          throw new AbortParsingBlockError();
        case BEFORE_ATTRIBUTE_VALUE:
          if (!willExactlyOneBranchExecuteOnce) {
            errorReporter.report(
                parent.getSourceLocation(),
                CONDITIONAL_BLOCK_ISNT_GUARANTEED_TO_PRODUCE_ONE_ATTRIBUTE_VALUE,
                overallName);
            // we continue and pretend like everything was ok
          }
          // theoretically we might want to support x={if $p}y{else}z{/if}w, in which case this
          // should be an attribute value part. We could support this if the branch ending state
          // was UNQUOTED_ATTRIBUTE_VALUE and at least one of the branches will execute
          if (willAtLeastOneBranchExecute && endingState == State.UNQUOTED_ATTRIBUTE_VALUE) {
            context.addAttributeValuePart(parent);
            context.setState(State.UNQUOTED_ATTRIBUTE_VALUE, endPoint);
          } else {
            context.setAttributeValue(parent);
            if (willAtLeastOneBranchExecute && endingState == State.BEFORE_ATTRIBUTE_NAME) {
              context.setState(State.BEFORE_ATTRIBUTE_NAME, endPoint);
            }
          }
          break;
        case UNQUOTED_ATTRIBUTE_VALUE:
        case DOUBLE_QUOTED_ATTRIBUTE_VALUE:
        case SINGLE_QUOTED_ATTRIBUTE_VALUE:
          context.addAttributeValuePart(parent);
          // no need to tweak any state, addAttributeValuePart doesn't modify anything
          break;
        case HTML_COMMENT:
        case NONE:
        case PCDATA:
        case RCDATA_SCRIPT:
        case RCDATA_STYLE:
        case RCDATA_TEXTAREA:
        case RCDATA_TITLE:
        case XML_DECLARATION:
        case CDATA:
        case DOUBLE_QUOTED_XML_ATTRIBUTE_VALUE:
        case SINGLE_QUOTED_XML_ATTRIBUTE_VALUE:
          // do nothing
          break;

        default:
          throw new AssertionError("unexpected control flow starting state: " + startingState);
      }
    }

    /** Visit the branches of a control flow structure. */
    State visitBranches(
        List<? extends BlockNode> children, Function<? super BlockNode, String> blockNamer) {
      Checkpoint checkpoint = errorReporter.checkpoint();
      State startState = context.getState();
      State endingState = null;
      for (BlockNode block : children) {
        String blockName = blockNamer.apply(block);
        ParsingContext newCtx =
            newParsingContext(blockName, startState, block.getSourceLocation().getBeginPoint());
        ParsingContext oldCtx = setContext(newCtx);
        endingState = visitBlock(startState, block, blockName, checkpoint);
        setContext(oldCtx); // restore
      }

      if (errorReporter.errorsSince(checkpoint)) {
        return startState;
      }
      return endingState;
    }

    /** Visits a block and returns the finalState. */
    State visitBlock(State startState, BlockNode node, String blockName, Checkpoint checkpoint) {
      try {
        visitChildren(node);
      } catch (AbortParsingBlockError abortProcessingError) {
        // we reported some error and just gave up on the block
        // try to switch back to a reasonable state based on the start state and keep going.
        switch (startState) {
          case AFTER_ATTRIBUTE_NAME:
          case AFTER_TAG_NAME_OR_ATTRIBUTE:
          case BEFORE_ATTRIBUTE_NAME:
          case BEFORE_ATTRIBUTE_VALUE:
          case SINGLE_QUOTED_ATTRIBUTE_VALUE:
          case DOUBLE_QUOTED_ATTRIBUTE_VALUE:
          case UNQUOTED_ATTRIBUTE_VALUE:
          case HTML_TAG_NAME:
            context.resetAttribute();
            context.setState(State.BEFORE_ATTRIBUTE_NAME, node.getSourceLocation().getEndPoint());
            break;
          case CDATA:
          case DOUBLE_QUOTED_XML_ATTRIBUTE_VALUE:
          case HTML_COMMENT:
          case NONE:
          case PCDATA:
          case RCDATA_SCRIPT:
          case RCDATA_STYLE:
          case RCDATA_TEXTAREA:
          case RCDATA_TITLE:
          case SINGLE_QUOTED_XML_ATTRIBUTE_VALUE:
          case XML_DECLARATION:
            context.reset();
            context.setState(startState, node.getSourceLocation().getEndPoint());
            break;
        }
      }
      context.finishBlock();
      State finalState = context.getState();
      SourceLocation.Point finalStateTransitionPoint = context.getStateTransitionPoint();
      if (finalState.isInvalidForEndOfBlock()) {
        errorReporter.report(
            node.getSourceLocation(), BLOCK_ENDS_IN_INVALID_STATE, blockName, finalState);
        finalState = startState;
      }
      if (!errorReporter.errorsSince(checkpoint)) {
        State reconciled = startState.reconcile(finalState);
        if (reconciled == null) {
          String suggestion = reconciliationFailureHint(startState, finalState);
          errorReporter.report(
              finalStateTransitionPoint.asLocation(filePath),
              BLOCK_CHANGES_CONTEXT,
              blockName,
              startState,
              finalState,
              suggestion != null ? " " + suggestion : "");
        } else {
          finalState = reconciled;
          reparentNodes(node, context, finalState);
        }
      } else {
        // an error occured, restore the start state to help avoid an error explosion
        finalState = startState;
      }
      context.setState(finalState, node.getSourceLocation().getEndPoint());
      return finalState;
    }

    /**
     * After visiting a block, this will transfer all the partial values in the blockCtx to the
     * parent.
     *
     * @param parent The block node
     * @param blockCtx The context after visiting the block
     * @param finalState The reconciled state after visiting the block
     */
    static void reparentNodes(BlockNode parent, ParsingContext blockCtx, State finalState) {
      // if there were no errors we may need to conditionally add new children, this only really
      // applies to attributes which may be partially finished (to allow for things like
      // foo=a{if $x}b{/if}
      // TODO(lukes): consider eliminating this method by moving the logic for reparenting into
      // ParsingContext and do it as part of creating the nodes.
      switch (finalState) {
        case AFTER_TAG_NAME_OR_ATTRIBUTE:
          blockCtx.maybeFinishPendingAttribute(parent.getSourceLocation().getEndPoint());
          // fall-through
        case BEFORE_ATTRIBUTE_NAME:
        case AFTER_ATTRIBUTE_NAME:
          blockCtx.reparentDirectTagChildren(parent);
          break;
        case UNQUOTED_ATTRIBUTE_VALUE:
        case DOUBLE_QUOTED_ATTRIBUTE_VALUE:
        case SINGLE_QUOTED_ATTRIBUTE_VALUE:
          blockCtx.reparentAttributeValueChildren(parent);
          break;
        case HTML_COMMENT:
        case NONE:
        case PCDATA:
        case RCDATA_SCRIPT:
        case RCDATA_STYLE:
        case RCDATA_TEXTAREA:
        case RCDATA_TITLE:
        case XML_DECLARATION:
        case CDATA:
        case DOUBLE_QUOTED_XML_ATTRIBUTE_VALUE:
        case SINGLE_QUOTED_XML_ATTRIBUTE_VALUE:
          // do nothing.. there should be nothing in context
          break;
        case BEFORE_ATTRIBUTE_VALUE:
        case HTML_TAG_NAME:
          // impossible?
        default:
          throw new AssertionError(
              "found non-empty context for unexpected state: " + blockCtx.getState());
      }
      blockCtx.checkEmpty("context not fully reparented after '%s'", finalState);
    }

    /** Gives a hint when we fail to reconcile to states. */
    static String reconciliationFailureHint(State startState, State finalState) {
      switch (finalState) {
        case PCDATA: // we could suggest for this one based on the start state maybe?
          return null; // no suggestion
        case BEFORE_ATTRIBUTE_VALUE:
          return "Expected an attribute value before the end of the block";
        case CDATA:
          return didYouForgetToCloseThe("CDATA section");
        case SINGLE_QUOTED_ATTRIBUTE_VALUE:
        case DOUBLE_QUOTED_ATTRIBUTE_VALUE:
        case DOUBLE_QUOTED_XML_ATTRIBUTE_VALUE:
        case SINGLE_QUOTED_XML_ATTRIBUTE_VALUE:
          return didYouForgetToCloseThe("attribute value");
        case HTML_COMMENT:
          return didYouForgetToCloseThe("html comment");
        case RCDATA_SCRIPT:
          return didYouForgetToCloseThe("<script> block");
        case RCDATA_STYLE:
          return didYouForgetToCloseThe("<style> block");
        case RCDATA_TEXTAREA:
          return didYouForgetToCloseThe("<textare> block");
        case RCDATA_TITLE:
          return didYouForgetToCloseThe("<title> block");
        case HTML_TAG_NAME: // kind of crazy
        case AFTER_ATTRIBUTE_NAME:
        case AFTER_TAG_NAME_OR_ATTRIBUTE:
        case BEFORE_ATTRIBUTE_NAME:
        case XML_DECLARATION:
        case UNQUOTED_ATTRIBUTE_VALUE:
          // if this wasn't reconciled, it means they probably forgot to close the tag
          if (startState == State.PCDATA) {
            return "Did you forget to close the tag?";
          }
          return null;
        case NONE: // should be impossible, there are no transitions into NONE from non-NONE
      }
      throw new AssertionError("unexpected final state: " + finalState);
    }

    static String didYouForgetToCloseThe(String thing) {
      return "Did you forget to close the " + thing + "?";
    }

    ParsingContext setContext(ParsingContext ctx) {
      ParsingContext old = context;
      context = ctx;
      return old;
    }

    /** @param state The state to start the context in. */
    ParsingContext newParsingContext(
        String blockName, State state, SourceLocation.Point startPoint) {
      return new ParsingContext(
          blockName, state, startPoint, filePath, edits, errorReporter, nodeIdGen);
    }
  }

  /**
   * A class to record all the edits to the AST we need to make.
   *
   * <p>Instead of editing the AST as we go, we record the edits and wait until the end, this makes
   * a few things easier.
   *
   * <ul>
   *   <li>we don't have to worry about editing nodes while visiting them
   *   <li>we can easily avoid making any edits if errors were recorded.
   *       <p>This is encapsulated in its own class so we can easily pass it around and to provide
   *       some encapsulation.
   * </ul>
   */
  private static final class AstEdits {
    final Set<StandaloneNode> toRemove = new LinkedHashSet<>();
    final ListMultimap<StandaloneNode, StandaloneNode> replacements =
        MultimapBuilder.linkedHashKeys().arrayListValues().build();
    final ListMultimap<ParentSoyNode<StandaloneNode>, StandaloneNode> newChildren =
        MultimapBuilder.linkedHashKeys().arrayListValues().build();

    /** Apply all edits. */
    void apply() {
      for (StandaloneNode nodeToRemove : toRemove) {
        ParentSoyNode<StandaloneNode> parent = nodeToRemove.getParent();
        int index = parent.getChildIndex(nodeToRemove);
        // NOTE:  we need to remove the child before adding the new children  to handle the case
        // where we are doing a no-op replacement or the replacement nodes contains nodeToRemove.
        // no-op replacements can occur when there are pcdata sections that contain no tags
        parent.removeChild(index);
        List<StandaloneNode> children = replacements.get(nodeToRemove);
        if (!children.isEmpty()) {
          parent.addChildren(index, children);
        }
      }
      for (Map.Entry<ParentSoyNode<StandaloneNode>, List<StandaloneNode>> entry :
          asMap(newChildren).entrySet()) {
        entry.getKey().addChildren(entry.getValue());
      }
      clear();
    }

    /** Mark a node for removal. */
    void remove(StandaloneNode node) {
      checkNotNull(node);
      // only record this if the node is actually in the tree already.  Sometimes we call remove
      // on new nodes that don't have parents yet.
      if (node.getParent() != null) {
        toRemove.add(node);
      }
    }

    /** Add children to the given parent. */
    void addChildren(ParentSoyNode<StandaloneNode> parent, Iterable<StandaloneNode> children) {
      checkNotNull(parent);
      newChildren.putAll(parent, children);
    }

    /** Adds the child to the given parent. */
    void addChild(ParentSoyNode<StandaloneNode> parent, StandaloneNode child) {
      checkNotNull(parent);
      checkNotNull(child);
      newChildren.put(parent, child);
    }

    /** Replace a given node with the new nodes. */
    void replace(StandaloneNode oldNode, Iterable<StandaloneNode> newNodes) {
      checkState(oldNode.getParent() != null, "oldNode must be in the tree in order to replace it");
      remove(oldNode);
      replacements.putAll(oldNode, newNodes);
    }

    /** Replace a given node with the new node. */
    void replace(StandaloneNode oldNode, StandaloneNode newNode) {
      replace(oldNode, ImmutableList.of(newNode));
    }

    /** Clear all the edits. */
    void clear() {
      toRemove.clear();
      replacements.clear();
      newChildren.clear();
    }
  }

  /**
   * Parsing context records the current {@link State} as well as all the extra information needed
   * to produce our new nodes.
   *
   * <p>This is extracted into a separate class so we can create new ones when visiting branches.
   *
   * <p>This isn't a perfect abstraction. It is set up to parse full HTML tags, but sometimes we
   * only want to parse attributes or attribute values (or parts of attribute values). In theory, we
   * could split it into 3-4 classes one for each case, but I'm not sure it simplifies things over
   * the current solution. See {@link Visitor#reparentNodes} for how we handle those cases.
   *
   * <p>The handling of attributes is particularly tricky. It is difficult to decide when to
   * complete an attribute (that is, create the {@link HtmlAttributeNode}). In theory it should be
   * obvious, the attribute is 'done' when we see one of the following:
   *
   * <ul>
   *   <li>A non '=' character after an attribute name (a value-less attribute)
   *   <li>A ' or " character after a quoted attribute value
   *   <li>A whitespace character after an unquoted attribute value
   * </ul>
   *
   * However, we want to support various control flow for creating attribute values. For example,
   * <code>href={if $v2}"/foo2"{else}"/foo"{/if}</code>. Here when we see the closing double quote
   * characters we know that the attribute value is done, but it is too early to close the
   * attribute. So we need to delay. Thus the rules for when we 'finish' an attribute are:
   *
   * <ul>
   *   <li>If a block starts in {@link State#BEFORE_ATTRIBUTE_VALUE} then the block must be the
   *       attribute value
   *   <li>If we see the beginning of a new attribute, we should finish the previous one
   *   <li>If we see the end of a tag, we should finish the previous attribute.
   *   <li>At the end of a block, we should complete attribute nodes if the block started in a tag
   *       state
   * </ul>
   */
  private static final class ParsingContext {
    final String blockName;
    final State startingState;
    final String filePath;
    final IdGenerator nodeIdGen;
    final ErrorReporter errorReporter;
    final AstEdits edits;

    // The current parser state.
    State state;
    SourceLocation.Point stateTransitionPoint;

    // for tracking the current tag being built

    /** Whether the current tag is a close tag. e.g. {@code </div>} */
    boolean isCloseTag;

    SourceLocation.Point tagStartPoint;
    RawTextNode tagStartNode;
    TagName tagName;

    // TODO(lukes): consider lazily allocating these lists.
    /** All the 'direct' children of the current tag. */
    final List<StandaloneNode> directTagChildren = new ArrayList<>();

    // for tracking the current attribute being built

    StandaloneNode attributeName;

    SourceLocation.Point equalsSignLocation;
    StandaloneNode attributeValue;

    // For the attribute value,  where the quoted attribute value started

    /** Where the open quote of a quoted attribute value starts. */
    SourceLocation.Point quotedAttributeValueStart;

    /** all the direct children of the attribute value. */
    final List<StandaloneNode> attributeValueChildren = new ArrayList<>();

    ParsingContext(
        String blockName,
        State startingState,
        SourceLocation.Point startPoint,
        String filePath,
        AstEdits edits,
        ErrorReporter errorReporter,
        IdGenerator nodeIdGen) {
      this.blockName = checkNotNull(blockName);
      this.startingState = checkNotNull(startingState);
      this.state = checkNotNull(startingState);
      this.stateTransitionPoint = checkNotNull(startPoint);
      this.filePath = checkNotNull(filePath);
      this.nodeIdGen = checkNotNull(nodeIdGen);
      this.edits = checkNotNull(edits);
      this.errorReporter = checkNotNull(errorReporter);
    }

    /** Called at the end of a block to finish any pending attribute nodes. */
    void finishBlock() {
      if (startingState.isTagState()) {
        maybeFinishPendingAttribute(stateTransitionPoint);
      }
    }

    /** Attaches the attributeValueChildren to the parent. */
    void reparentAttributeValueChildren(BlockNode parent) {
      edits.addChildren(parent, attributeValueChildren);
      attributeValueChildren.clear();
    }

    /** Attaches the directTagChildren to the parent. */
    void reparentDirectTagChildren(BlockNode parent) {
      if (attributeValue != null) {
        edits.addChild(parent, attributeValue);
        attributeValue = null;
      }

      edits.addChildren(parent, directTagChildren);
      directTagChildren.clear();
    }

    /** Returns true if this has accumulated parts of an unquoted attribute value. */
    boolean hasUnquotedAttributeValueParts() {
      return quotedAttributeValueStart == null && !attributeValueChildren.isEmpty();
    }

    /** Returns true if this has accumulated parts of a quoted attribute value. */
    boolean hasQuotedAttributeValueParts() {
      return quotedAttributeValueStart != null;
    }

    boolean hasTagStart() {
      return tagStartNode != null && tagStartPoint != null;
    }

    /** Sets the given node as a direct child of the tag currently being built. */
    void addTagChild(StandaloneNode node) {
      maybeFinishPendingAttribute(node.getSourceLocation().getBeginPoint());
      checkNotNull(node);
      directTagChildren.add(node);
      edits.remove(node);
      setState(State.AFTER_TAG_NAME_OR_ATTRIBUTE, node.getSourceLocation().getEndPoint());
    }

    /** Asserts that the context is empty. */
    void checkEmpty(String fmt, Object... args) {
      StringBuilder error = null;

      if (!directTagChildren.isEmpty()) {
        error = format(error, "Expected directTagChildren to be empty, got: %s", directTagChildren);
      }
      if (attributeName != null) {
        error = format(error, "Expected attributeName to be null, got: %s", attributeName);
      }
      if (equalsSignLocation != null) {
        error =
            format(error, "Expected equalsSignLocation to be null, got: %s", equalsSignLocation);
      }
      if (attributeValue != null) {
        error = format(error, "Expected attributeValue to be null, got: %s", attributeValue);
      }
      if (!attributeValueChildren.isEmpty()) {
        error =
            format(
                error,
                "Expected attributeValueChildren to be empty, got: %s",
                attributeValueChildren);
      }
      if (tagStartPoint != null) {
        error = format(error, "Expected tagStartPoint to be null, got: %s", tagStartPoint);
      }
      if (tagName != null) {
        error = format(error, "Expected tagName to be null, got: %s", tagName);
      }
      if (tagStartNode != null) {
        error = format(error, "Expected tagStartNode to be null, got: %s", tagStartNode);
      }
      if (quotedAttributeValueStart != null) {
        error =
            format(
                error,
                "Expected quotedAttributeValueStart to be null, got: %s",
                quotedAttributeValueStart);
      }
      if (tagName != null) {
        error = format(error, "Expected tagName to be null, got: %s", tagName);
      }
      if (error != null) {
        throw new IllegalStateException(String.format(fmt + "\n", args) + error);
      }
    }

    private static StringBuilder format(StringBuilder error, String fmt, Object... args) {
      if (error == null) {
        error = new StringBuilder();
      }
      error.append(String.format(fmt, args));
      error.append('\n');
      return error;
    }

    /** Resets all parsing state, this is useful for error recovery. */
    void reset() {
      tagStartPoint = null;
      tagStartNode = null;
      tagName = null;
      directTagChildren.clear();
      resetAttribute();
    }

    void resetAttribute() {
      attributeName = null;
      equalsSignLocation = null;
      attributeValue = null;
      quotedAttributeValueStart = null;
      attributeValueChildren.clear();
    }

    /**
     * Records the start of an html tag
     *
     * @param tagStartNode The node where it started
     * @param isCloseTag is is a close tag
     * @param point the source location of the {@code <} character.
     */
    void startTag(RawTextNode tagStartNode, boolean isCloseTag, SourceLocation.Point point) {
      checkState(this.tagStartPoint == null);
      checkState(this.tagStartNode == null);
      checkState(this.directTagChildren.isEmpty());

      // need to check if it is safe to transition into a tag.
      // this is only true if our starting location is pcdata
      if (startingState != State.PCDATA) {
        errorReporter.report(
            point.asLocation(filePath),
            BLOCK_TRANSITION_DISALLOWED,
            blockName,
            startingState,
            "tag");
        throw new AbortParsingBlockError();
      }

      this.tagStartPoint = checkNotNull(point);
      this.tagStartNode = checkNotNull(tagStartNode);
      this.isCloseTag = isCloseTag;
    }

    /** Returns the tag start location, for error reporting. */
    SourceLocation tagStartLocation() {
      return tagStartPoint.asLocation(filePath);
    }

    /** Sets the tag name of the tag currently being built. */
    void setTagName(TagName tagName) {
      this.tagName = checkNotNull(tagName);
      edits.remove(tagName.getNode());
      setState(State.AFTER_TAG_NAME_OR_ATTRIBUTE, tagName.getTagLocation().getEndPoint());
    }

    void startAttribute(StandaloneNode attrName) {
      maybeFinishPendingAttribute(attrName.getSourceLocation().getBeginPoint());
      checkNotNull(attrName);
      checkState(attributeName == null);
      if (startingState == State.BEFORE_ATTRIBUTE_VALUE) {
        errorReporter.report(
            attrName.getSourceLocation(),
            BLOCK_TRANSITION_DISALLOWED,
            blockName,
            startingState,
            "attribute");
        throw new AbortParsingBlockError();
      }
      edits.remove(attrName);
      attributeName = attrName;
      setState(State.AFTER_ATTRIBUTE_NAME, attrName.getSourceLocation().getEndPoint());
    }

    void setEqualsSignLocation(
        SourceLocation.Point equalsSignPoint, SourceLocation.Point stateTransitionPoint) {
      checkNotNull(equalsSignPoint);
      if (attributeName == null) {
        // the attribute must have been started in another block
        errorReporter.report(
            stateTransitionPoint.asLocation(filePath), FOUND_EQ_WITH_ATTRIBUTE_IN_ANOTHER_BLOCK);
        throw new AbortParsingBlockError();
      }
      checkState(equalsSignLocation == null);
      equalsSignLocation = equalsSignPoint;
      setState(State.BEFORE_ATTRIBUTE_VALUE, stateTransitionPoint);
    }

    void setAttributeValue(StandaloneNode node) {
      checkNotNull(node);
      checkState(
          attributeValue == null, "attribute value already set at: %s", node.getSourceLocation());
      edits.remove(node);
      attributeValue = node;
      setState(State.AFTER_TAG_NAME_OR_ATTRIBUTE, node.getSourceLocation().getEndPoint());
    }

    /**
     * Records the start of a quoted attribute value.
     *
     * @param node The node where it started
     * @param point The source location where it started.
     * @param nextState The next state, either {@link State#DOUBLE_QUOTED_ATTRIBUTE_VALUE} or {@link
     *     State#SINGLE_QUOTED_ATTRIBUTE_VALUE}.
     */
    void startQuotedAttributeValue(RawTextNode node, SourceLocation.Point point, State nextState) {
      checkState(!hasQuotedAttributeValueParts());
      checkState(!hasUnquotedAttributeValueParts());
      edits.remove(node);
      quotedAttributeValueStart = checkNotNull(point);
      setState(nextState, point);
    }

    /** Adds a new attribute value part and marks the node for removal. */
    void addAttributeValuePart(StandaloneNode node) {
      attributeValueChildren.add(node);
      edits.remove(node);
    }

    /** Completes an unquoted attribute value. */
    void createUnquotedAttributeValue(SourceLocation.Point endPoint) {
      if (!hasUnquotedAttributeValueParts()) {
        if (attributeName != null) {
          errorReporter.report(endPoint.asLocation(filePath), EXPECTED_ATTRIBUTE_VALUE);
        } else {
          errorReporter.report(
              endPoint.asLocation(filePath), FOUND_END_OF_ATTRIBUTE_STARTED_IN_ANOTHER_BLOCK);
          throw new AbortParsingBlockError();
        }
        resetAttribute();
        setState(State.AFTER_TAG_NAME_OR_ATTRIBUTE, endPoint);
        return;
      }
      HtmlAttributeValueNode valueNode =
          new HtmlAttributeValueNode(
              nodeIdGen.genId(), getLocationOf(attributeValueChildren), Quotes.NONE);
      edits.addChildren(valueNode, attributeValueChildren);
      attributeValueChildren.clear();
      setAttributeValue(valueNode);
    }

    /** Completes a quoted attribute value. */
    void createQuotedAttributeValue(
        RawTextNode end, boolean doubleQuoted, SourceLocation.Point endPoint) {
      HtmlAttributeValueNode valueNode =
          new HtmlAttributeValueNode(
              nodeIdGen.genId(),
              new SourceLocation(filePath, quotedAttributeValueStart, endPoint),
              doubleQuoted ? Quotes.DOUBLE : Quotes.SINGLE);
      edits.remove(end);
      edits.addChildren(valueNode, attributeValueChildren);
      attributeValueChildren.clear();
      quotedAttributeValueStart = null;
      setAttributeValue(valueNode);
    }

    /**
     * Creates an HtmlOpenTagNode or an HtmlCloseTagNode
     *
     * @param tagEndNode The node where the tag ends
     * @param selfClosing Whether it is self closing, e.g. {@code <div />} is self closing
     * @param endPoint The point where the {@code >} character is.
     * @return The state to transition into, typically this is {@link State#PCDATA} but could be one
     *     of the rcdata states for special tags.
     */
    State createTag(RawTextNode tagEndNode, boolean selfClosing, SourceLocation.Point endPoint) {
      maybeFinishPendingAttribute(endPoint);
      ParentSoyNode<StandaloneNode> replacement;
      SourceLocation sourceLocation = new SourceLocation(filePath, tagStartPoint, endPoint);
      if (isCloseTag) {
        // TODO(lukes): move the error reporting into the caller?
        if (!directTagChildren.isEmpty()) {
          errorReporter.report(
              directTagChildren.get(0).getSourceLocation(), UNEXPECTED_CLOSE_TAG_CONTENT);
        }
        if (selfClosing) {
          errorReporter.report(
              endPoint.asLocation(filePath).offsetStartCol(-1), SELF_CLOSING_CLOSE_TAG);
        }
        replacement = new HtmlCloseTagNode(nodeIdGen.genId(), tagName, sourceLocation);
      } else {
        replacement = new HtmlOpenTagNode(nodeIdGen.genId(), tagName, sourceLocation, selfClosing);
      }
      // Depending on the tag name, we may need to enter a special state after the tag.
      State nextState = State.PCDATA;
      if (!selfClosing && !isCloseTag) {
        TagName.RcDataTagName rcDataTag = tagName.getRcDataTagName();
        if (rcDataTag != null) {
          switch (rcDataTag) {
            case SCRIPT:
              nextState = State.RCDATA_SCRIPT;
              break;
            case STYLE:
              nextState = State.RCDATA_STYLE;
              break;
            case TEXTAREA:
              nextState = State.RCDATA_TEXTAREA;
              break;
            case TITLE:
              nextState = State.RCDATA_TITLE;
              break;
            default:
              throw new AssertionError(rcDataTag);
          }
        }
      }
      edits.remove(tagEndNode);
      edits.addChild(replacement, tagName.getNode());
      edits.addChildren(replacement, directTagChildren);
      // cast is safe because Html(Open|Close)TagNode implement StandaloneNode
      edits.replace(tagStartNode, (StandaloneNode) replacement);
      directTagChildren.clear();
      tagStartPoint = null;
      tagName = null;
      tagStartNode = null;
      checkEmpty("Expected state to be empty after completing a tag");
      return nextState;
    }

    void maybeFinishPendingAttribute(SourceLocation.Point currentPoint) {
      // For quoted attribute values we should have already finished them (when we saw the closing
      // quote).  But for unquoted attribute values we delay closing them until we see a delimiter
      // so create one now if we have parts.
      if (hasUnquotedAttributeValueParts()) {
        createUnquotedAttributeValue(currentPoint);
      } else if (hasQuotedAttributeValueParts()) {
        // if there is a quoted attribute, it should have been finished
        // which means the only way we could get here is if the attribute was not finished
        // in a block
        errorReporter.report(
            currentPoint.asLocation(filePath), FOUND_END_OF_ATTRIBUTE_STARTED_IN_ANOTHER_BLOCK);
        throw new AbortParsingBlockError();
      }
      if (attributeName != null) {
        SourceLocation location = attributeName.getSourceLocation();
        HtmlAttributeNode attribute;
        if (attributeValue != null) {
          attribute =
              new HtmlAttributeNode(nodeIdGen.genId(), location, checkNotNull(equalsSignLocation));
          location = location.extend(attributeValue.getSourceLocation());
          edits.addChild(attribute, attributeName);
          edits.addChild(attribute, attributeValue);
        } else {
          attribute = new HtmlAttributeNode(nodeIdGen.genId(), location, null);
          edits.addChild(attribute, attributeName);
        }
        attributeName = null;
        equalsSignLocation = null;
        attributeValue = null;
        // We don't call addDirectTagChild to avoid
        // 1. calling maybeFinishPendingAttribute recursively
        // 2. to avoid changing the state field
        directTagChildren.add(attribute);
        edits.remove(attribute);
      }
    }

    /**
     * Changes the state of this context.
     *
     * @param s the new state
     * @param point the current location where the transition ocurred.
     * @return the previous state
     */
    State setState(State s, SourceLocation.Point point) {
      State old = state;
      state = checkNotNull(s);
      stateTransitionPoint = checkNotNull(point);
      if (DEBUG) {
        System.err.println(
            point.asLocation(filePath) + "\tState: " + s.name() + " errors: " + errorReporter);
      }
      return old;
    }

    State getState() {
      checkState(state != null);
      return state;
    }

    SourceLocation.Point getStateTransitionPoint() {
      checkState(stateTransitionPoint != null);
      return stateTransitionPoint;
    }

    static SourceLocation getLocationOf(List<StandaloneNode> nodes) {
      SourceLocation location = nodes.get(0).getSourceLocation();
      if (nodes.size() > 1) {
        location = location.extend(Iterables.getLast(nodes).getSourceLocation());
      }
      return location;
    }
  }
  
  /** A custom error to halt processing of a given control flow block. */
  private static final class AbortParsingBlockError extends Error {}
}
