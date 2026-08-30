package juloo.keyboard2;

/** Standard two-set (두벌식) Hangul input automaton.

    Jamo are fed one by one with [input] and the automaton maintains the
    syllable currently being composed. Every call returns the text that must be
    committed to the editor and the new content of the composing region.

    This class doesn't depend on Android classes so that it can be unit
    tested. */
public final class HangulComposer
{
  /** The 19 initials (초성), indexed by their position in a precomposed
      syllable. */
  static final String INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
  /** The 21 medials (중성). */
  static final String MEDIALS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
  /** The 28 finals (종성). Index [0] is the absence of a final. */
  static final String FINALS = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";

  /** Medials that combine into a diphthong, as { first, second, result }. */
  static final char[][] MEDIAL_COMBOS = {
    { 'ㅗ', 'ㅏ', 'ㅘ' }, { 'ㅗ', 'ㅐ', 'ㅙ' }, { 'ㅗ', 'ㅣ', 'ㅚ' },
    { 'ㅜ', 'ㅓ', 'ㅝ' }, { 'ㅜ', 'ㅔ', 'ㅞ' }, { 'ㅜ', 'ㅣ', 'ㅟ' },
    { 'ㅡ', 'ㅣ', 'ㅢ' },
  };

  /** Finals that combine into a compound final. Tense consonants are absent on
      purpose, they are typed with shift rather than composed. */
  static final char[][] FINAL_COMBOS = {
    { 'ㄱ', 'ㅅ', 'ㄳ' }, { 'ㄴ', 'ㅈ', 'ㄵ' }, { 'ㄴ', 'ㅎ', 'ㄶ' },
    { 'ㄹ', 'ㄱ', 'ㄺ' }, { 'ㄹ', 'ㅁ', 'ㄻ' }, { 'ㄹ', 'ㅂ', 'ㄼ' },
    { 'ㄹ', 'ㅅ', 'ㄽ' }, { 'ㄹ', 'ㅌ', 'ㄾ' }, { 'ㄹ', 'ㅍ', 'ㄿ' },
    { 'ㄹ', 'ㅎ', 'ㅀ' }, { 'ㅂ', 'ㅅ', 'ㅄ' },
  };

  /** What the caller must do to the editor after feeding the automaton. */
  public static final class Result
  {
    /** Text to commit before the composing region. Might be empty. */
    public final String commit;
    /** The new content of the composing region. Empty when nothing is being
        composed anymore. */
    public final String composing;

    Result(String commit_, String composing_)
    {
      commit = commit_;
      composing = composing_;
    }
  }

  /** Index into [INITIALS], [-1] if absent. */
  int _cho = -1;
  /** Index into [MEDIALS], [-1] if absent. */
  int _jung = -1;
  /** Index into [FINALS], [0] if absent. */
  int _jong = 0;

  public HangulComposer() {}

  /** Whether [c] is a compatibility jamo this automaton can consume. */
  public static boolean is_jamo(char c)
  {
    return c >= 'ㄱ' && c <= 'ㅣ';
  }

  static boolean is_vowel(char c)
  {
    return c >= 'ㅏ' && c <= 'ㅣ';
  }

  public boolean is_composing()
  {
    return _cho >= 0 || _jung >= 0;
  }

  public void clear()
  {
    _cho = -1;
    _jung = -1;
    _jong = 0;
  }

  /** The syllable being composed. Empty if there is none. */
  public String composing()
  {
    if (_cho >= 0 && _jung >= 0)
      return String.valueOf((char)(0xAC00 + _cho * 588 + _jung * 28 + _jong));
    if (_cho >= 0)
      return String.valueOf(INITIALS.charAt(_cho));
    if (_jung >= 0)
      return String.valueOf(MEDIALS.charAt(_jung));
    return "";
  }

  /** Return the syllable being composed and reset the state. */
  public String flush()
  {
    String s = composing();
    clear();
    return s;
  }

  /** Feed a jamo to the automaton. */
  public Result input(char c)
  {
    return is_vowel(c) ? input_medial(c) : input_consonant(c);
  }

  /** Remove the last jamo. Returns [null] if nothing was being composed, in
      which case the caller must handle the backspace itself. */
  public Result backspace()
  {
    if (!is_composing())
      return null;
    if (_jong != 0)
    {
      char[] dec = decompose(FINAL_COMBOS, FINALS.charAt(_jong));
      _jong = (dec == null) ? 0 : FINALS.indexOf(dec[0]);
    }
    else if (_jung >= 0)
    {
      char[] dec = decompose(MEDIAL_COMBOS, MEDIALS.charAt(_jung));
      _jung = (dec == null) ? -1 : MEDIALS.indexOf(dec[0]);
    }
    else
      _cho = -1;
    return result("");
  }

  /** A consonant is a final of the current syllable when it can be, otherwise
      it starts a new syllable. */
  Result input_consonant(char c)
  {
    if (_cho >= 0 && _jung >= 0)
    {
      if (_jong == 0)
      {
        int fin = FINALS.indexOf(c);
        if (fin > 0)
        {
          _jong = fin;
          return result("");
        }
      }
      else
      {
        char comb = combine(FINAL_COMBOS, FINALS.charAt(_jong), c);
        if (comb != 0)
        {
          _jong = FINALS.indexOf(comb);
          return result("");
        }
      }
    }
    String commit = flush();
    int ini = INITIALS.indexOf(c);
    if (ini < 0) // A compound final typed on its own, it is not an initial.
      return new Result(commit + c, "");
    _cho = ini;
    return result(commit);
  }

  Result input_medial(char c)
  {
    int med = MEDIALS.indexOf(c);
    if (_jong != 0)
    {
      // The final belongs to the syllable this vowel starts. Compound finals
      // are split and only their second half is moved.
      char jong = FINALS.charAt(_jong);
      char[] dec = decompose(FINAL_COMBOS, jong);
      char moved;
      if (dec == null)
      {
        _jong = 0;
        moved = jong;
      }
      else
      {
        _jong = FINALS.indexOf(dec[0]);
        moved = dec[1];
      }
      String commit = flush();
      _cho = INITIALS.indexOf(moved);
      _jung = med;
      return result(commit);
    }
    if (_jung >= 0)
    {
      char comb = combine(MEDIAL_COMBOS, MEDIALS.charAt(_jung), c);
      if (comb != 0)
      {
        _jung = MEDIALS.indexOf(comb);
        return result("");
      }
      String commit = flush();
      _jung = med;
      return result(commit);
    }
    _jung = med;
    return result("");
  }

  Result result(String commit)
  {
    return new Result(commit, composing());
  }

  /** Returns [0] if the two jamo do not combine. */
  static char combine(char[][] table, char a, char b)
  {
    for (int i = 0; i < table.length; i++)
      if (table[i][0] == a && table[i][1] == b)
        return table[i][2];
    return 0;
  }

  /** Returns the row of [table] producing [c], [null] if [c] is not a compound
      jamo. */
  static char[] decompose(char[][] table, char c)
  {
    for (int i = 0; i < table.length; i++)
      if (table[i][2] == c)
        return table[i];
    return null;
  }
}
