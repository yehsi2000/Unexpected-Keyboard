package juloo.keyboard2;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.IBinder;
import android.text.InputType;
import android.util.Log;
import android.util.LogPrinter;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import juloo.cdict.Cdict;
import juloo.keyboard2.dict.Dictionaries;
import juloo.keyboard2.dict.DictionariesActivity;
import juloo.keyboard2.dict.DictionarySwitcher;
import juloo.keyboard2.prefs.LayoutsPreference;
import juloo.keyboard2.suggestions.CandidatesView;
import juloo.keyboard2.suggestions.Suggestions;

public class Keyboard2 extends InputMethodService
  implements SharedPreferences.OnSharedPreferenceChangeListener
{
  /** The view containing the keyboard and candidates view. */
  private ViewGroup _keyboard_container_view;
  private Keyboard2View _keyboard_layout_view;
  private CandidatesView _candidates_view;
  private Suggestions _suggestions;
  private KeyEventHandler _keyeventhandler;
  /** If not 'null', the layout to use instead of [_config.current_layout]. */
  private KeyboardData _currentSpecialLayout;
  /** Layout associated with the currently selected locale. Not 'null'. */
  private KeyboardData _localeTextLayout;
  /** Installed and current locales. */
  private Dictionaries _dictionaries;
  private ViewGroup _emojiPane = null;
  private ViewGroup _clipboard_pane = null;
  private Handler _handler;

  private Config _config;

  private FoldStateTracker _foldStateTracker;

  /** Layout currently visible before it has been modified. */
  KeyboardData current_layout_unmodified()
  {
    if (_currentSpecialLayout != null)
      return _currentSpecialLayout;
    KeyboardData layout = null;
    int layout_i = _config.get_current_layout();
    if (layout_i >= _config.layouts.size())
      layout_i = 0;
    if (layout_i < _config.layouts.size())
      layout = _config.layouts.get(layout_i);
    if (layout == null)
      layout = _localeTextLayout;
    return layout;
  }

  /** Layout currently visible. */
  KeyboardData current_layout()
  {
    if (_currentSpecialLayout != null)
      return _currentSpecialLayout;
    return LayoutModifier.modify_layout(current_layout_unmodified());
  }

  /** Set the layout shown by [_keyboard_layout_view] and enable Hangul
      composition in [_keyeventhandler] when its script is "hangul". */
  void set_layout_view(KeyboardData l)
  {
    _keyboard_layout_view.setKeyboard(l);
    _keyeventhandler.set_hangul_enabled("hangul".equals(l.script));
  }

  void setTextLayout(int l)
  {
    _config.set_current_layout(l);
    _currentSpecialLayout = null;
    // The active dictionary depends on the current layout.
    refresh_current_dictionary();
    refresh_candidates_view();
    set_layout_view(current_layout());
  }

  void incrTextLayout(int delta)
  {
    int s = _config.layouts.size();
    setTextLayout((_config.get_current_layout() + delta + s) % s);
  }

  void setSpecialLayout(KeyboardData l)
  {
    _currentSpecialLayout = l;
    set_layout_view(l);
  }

  KeyboardData loadLayout(int layout_id)
  {
    return KeyboardData.load(getResources(), layout_id);
  }

  /** Load a layout that contains a numpad. */
  KeyboardData loadNumpad(int layout_id)
  {
    return LayoutModifier.modify_numpad(KeyboardData.load(getResources(), layout_id),
        current_layout_unmodified());
  }

  KeyboardData loadNumericLayout()
  {
    return loadNumpad(_config.orientation_landscape ?
        R.xml.numeric_landscape : R.xml.numeric);
  }

  KeyboardData loadPinentry(int layout_id)
  {
    return LayoutModifier.modify_pinentry(KeyboardData.load(getResources(), layout_id),
        current_layout_unmodified());
  }

  @Override
  public void onCreate()
  {
    super.onCreate();
    SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
    _handler = new Handler(getMainLooper());
    _foldStateTracker = new FoldStateTracker(this);
    _dictionaries = Dictionaries.instance(this);
    Config.initGlobalConfig(prefs, getResources(),
        _foldStateTracker.isUnfolded(), _dictionaries);
    _config = Config.globalConfig();
    Receiver recvr = this.new Receiver();
    _suggestions = new Suggestions(recvr, _config);
    _keyeventhandler = new KeyEventHandler(recvr, _suggestions);
    KeyValue.Stateful._handler = recvr;
    _config.handler = _keyeventhandler;
    prefs.registerOnSharedPreferenceChangeListener(this);
    Logs.set_debug_logs(getResources().getBoolean(R.bool.debug_logs));
    refreshSubtypeImm();
    create_keyboard_view();
    ClipboardHistoryService.on_startup(this, _keyeventhandler);
    _foldStateTracker.setChangedCallback(() -> { refresh_config(); });
  }

  @Override
  public void onDestroy() {
    super.onDestroy();

    _foldStateTracker.close();
  }

  private void create_keyboard_view()
  {
    _keyboard_container_view = (ViewGroup)inflate_view(R.layout.keyboard);
    _keyboard_layout_view = (Keyboard2View)_keyboard_container_view.findViewById(R.id.keyboard_view);
    _candidates_view = (CandidatesView)_keyboard_container_view.findViewById(R.id.candidates_view);
  }

  InputMethodManager get_imm()
  {
    return (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
  }

  private void refreshSubtypeImm()
  {
    _config.shouldOfferVoiceTyping = true;
    KeyboardData default_layout = null;
    _config.device_locales = DeviceLocales.load(this);
    if (_config.device_locales.default_ != null)
    {
      String layout_name = _config.device_locales.default_.default_layout;
      if (layout_name != null)
        default_layout = LayoutsPreference.layout_of_string(getResources(), layout_name);
    }
    _config.extra_keys_subtype = _config.device_locales.extra_keys();
    if (default_layout == null)
      default_layout = loadLayout(R.xml.latn_qwerty_us);
    _localeTextLayout = default_layout;
  }

  private void refresh_current_dictionary()
  {
    _config.should_show_dictionary_switch =
      (_config.device_locales.installed.size() > 0);
    String dict_name = _dictionaries.get_selected(_config);
    if (dict_name == null)
      dict_name = (_config.device_locales.default_ != null) ?
        _config.device_locales.default_.dictionary : null;
    _dictionaries.set_current_dictionary(_config, dict_name);
  }

  /** Remember and apply the dictionary chosen by the user for the current
      context. */
  private void select_dictionary(String dict_name)
  {
    _dictionaries.set_selected(_config, dict_name);
    refresh_current_dictionary();
    refresh_candidates_view();
  }

  private void refresh_candidates_view()
  {
    boolean should_show =
      _config.suggestions_enabled
      && _config.editor_config.should_show_candidates_view
      && !_config.split_layout;
    if (should_show)
    {
      _candidates_view.refresh_config(_config);
      _keyeventhandler.dictionary_changed();
    }
    _candidates_view.setVisibility(should_show ? View.VISIBLE : View.GONE);
  }

  /** Might re-create the keyboard view. [_keyboard_layout_view.setKeyboard()] and
      [setInputView()] must be called soon after. */
  private void refresh_config()
  {
    int prev_theme = _config.theme;
    _config.refresh(getResources(), _foldStateTracker.isUnfolded(), _dictionaries);
    refresh_current_dictionary();
    // Refreshing the theme config requires re-creating the views
    if (prev_theme != _config.theme)
    {
      create_keyboard_view();
      _emojiPane = null;
      _clipboard_pane = null;
      setInputView(_keyboard_container_view);
    }
    // Set keyboard background opacity
    Drawable bg = _keyboard_container_view.getBackground().mutate();
    bg.setAlpha(_config.keyboardOpacity);
    _keyboard_container_view.setBackground(bg);
    _keyboard_layout_view.reset();
    refresh_candidates_view();
  }

  private KeyboardData refresh_special_layout()
  {
    if (_config.editor_config.numeric_layout)
    {
      switch (_config.selected_number_layout)
      {
        case PIN:
          return loadPinentry(_config.orientation_landscape ?
              R.xml.pin_landscape : R.xml.pin);
        case NUMBER:
          return loadNumericLayout();
      }
    }
    return null;
  }

  @Override
  public void onStartInputView(EditorInfo info, boolean restarting)
  {
    _config.editor_config.refresh(info, getResources());
    refresh_config();
    _currentSpecialLayout = refresh_special_layout();
    set_layout_view(current_layout());
    _keyeventhandler.started(_config);
    setInputView(_keyboard_container_view);
    Logs.debug_startup_input_view(info, _config);
  }

  @Override
  public void setExtractViewShown(boolean shown){
      super.setExtractViewShown(false);
  }

  @Override
  public void setInputView(View v)
  {
    ViewParent parent = v.getParent();
    if (parent != null && parent instanceof ViewGroup)
      ((ViewGroup)parent).removeView(v);
    super.setInputView(v);
    updateSoftInputWindowLayoutParams();
    v.requestApplyInsets();
  }

  @Override
  public void updateFullscreenMode() {
    super.updateFullscreenMode();
    updateSoftInputWindowLayoutParams();
  }

  private void updateSoftInputWindowLayoutParams() {
    final Window window = getWindow().getWindow();
    // On API >= 35, Keyboard2View behaves as edge-to-edge
    // APIs 30 to 34 have visual artifact when edge-to-edge is enabled
    if (VERSION.SDK_INT >= 35)
    {
      WindowManager.LayoutParams wattrs = window.getAttributes();
      wattrs.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
      // Allow to draw behind system bars
      wattrs.setFitInsetsTypes(0);
      window.setDecorFitsSystemWindows(false);
    }
    updateLayoutHeightOf(window, ViewGroup.LayoutParams.MATCH_PARENT);
    final View inputArea = window.findViewById(android.R.id.inputArea);

    updateLayoutHeightOf(
            (View) inputArea.getParent(),
            isFullscreenMode()
                    ? ViewGroup.LayoutParams.MATCH_PARENT
                    : ViewGroup.LayoutParams.WRAP_CONTENT);
    updateLayoutGravityOf((View) inputArea.getParent(), Gravity.BOTTOM);

  }

  private static void updateLayoutHeightOf(final Window window, final int layoutHeight) {
    final WindowManager.LayoutParams params = window.getAttributes();
    if (params != null && params.height != layoutHeight) {
      params.height = layoutHeight;
      window.setAttributes(params);
    }
  }

  private static void updateLayoutHeightOf(final View view, final int layoutHeight) {
    final ViewGroup.LayoutParams params = view.getLayoutParams();
    if (params != null && params.height != layoutHeight) {
      params.height = layoutHeight;
      view.setLayoutParams(params);
    }
  }

  private static void updateLayoutGravityOf(final View view, final int layoutGravity) {
    final ViewGroup.LayoutParams lp = view.getLayoutParams();
    if (lp instanceof LinearLayout.LayoutParams) {
      final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) lp;
      if (params.gravity != layoutGravity) {
        params.gravity = layoutGravity;
        view.setLayoutParams(params);
      }
    } else if (lp instanceof FrameLayout.LayoutParams) {
      final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) lp;
      if (params.gravity != layoutGravity) {
        params.gravity = layoutGravity;
        view.setLayoutParams(params);
      }
    }
  }

  @Override
  public void onCurrentInputMethodSubtypeChanged(InputMethodSubtype subtype)
  {
    refreshSubtypeImm();
    refresh_current_dictionary();
    refresh_candidates_view();
    set_layout_view(current_layout());
  }

  @Override
  public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd)
  {
    super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
    _keyeventhandler.selection_updated(oldSelStart, newSelStart, newSelEnd, candidatesStart);
    if ((oldSelStart == oldSelEnd) != (newSelStart == newSelEnd))
      _keyboard_layout_view.set_selection_state(newSelStart != newSelEnd);
  }

  @Override
  public void onFinishInputView(boolean finishingInput)
  {
    super.onFinishInputView(finishingInput);
    _keyeventhandler.finish_hangul();
    _keyboard_layout_view.reset();
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences _prefs, String _key)
  {
    refresh_config();
    set_layout_view(current_layout());
  }

  @Override
  public boolean onEvaluateFullscreenMode()
  {
    /* Entirely disable fullscreen mode. */
    return false;
  }

  @Override
  public boolean onEvaluateInputViewShown()
  {
    // Since Android 16, this method returns [false] for unknown reasons.
    if (super.onEvaluateInputViewShown())
      return true;
    if (getResources().getConfiguration().hardKeyboardHidden
        == Configuration.HARDKEYBOARDHIDDEN_NO
        && _config.physical_keyboard_hide)
    {
      Logs.debug("Physical keyboard is present");
      return false;
    }
    return true;
  }

  // K380: right Alt (한영 자리) -> 레이아웃 전환. Left Alt는 조합키 유지.
  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event)
  {
    if (keyCode == KeyEvent.KEYCODE_ALT_RIGHT)
    {
      if (event.getRepeatCount() == 0) incrTextLayout(1);
      return true;
    }
    // 물리 키보드 한글 입력: 현재 레이아웃이 hangul이면 QWERTY 위치를 현재 레이아웃의 같은 row/col로 매핑
    if ("hangul".equals(current_layout().script))
    {
      if (keyCode == KeyEvent.KEYCODE_DEL)
      {
        _config.handler.key_down(KeyValue.getKeyByName("backspace"), false);
        _config.handler.key_up(KeyValue.getKeyByName("backspace"), Pointers.Modifiers.EMPTY);
        return true;
      }
      if (keyCode == KeyEvent.KEYCODE_SPACE)
      {
        KeyValue kv = KeyValue.makeCharKey(' ');
        _config.handler.key_down(kv, false);
        _config.handler.key_up(kv, Pointers.Modifiers.EMPTY);
        return true;
      }
      if (keyCode == KeyEvent.KEYCODE_ENTER)
      {
        _config.handler.key_down(KeyValue.ENTER, false);
        _config.handler.key_up(KeyValue.ENTER, Pointers.Modifiers.EMPTY);
        return true;
      }
      char mapped = mapPhysicalKeyToHangul(keyCode, event);
      if (mapped != 0)
      {
        KeyValue kv = KeyValue.makeCharKey(mapped);
        _config.handler.key_down(kv, false);
        _config.handler.key_up(kv, Pointers.Modifiers.EMPTY);
        return true;
      }
    }
    return super.onKeyDown(keyCode, event);
  }

  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event)
  {
    if (keyCode == KeyEvent.KEYCODE_ALT_RIGHT) return true;
    if ("hangul".equals(current_layout().script))
    {
      if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_ENTER)
        return true;
      if (mapPhysicalKeyToHangul(keyCode, event) != 0) return true;
    }
    return super.onKeyUp(keyCode, event);
  }

  private char mapPhysicalKeyToHangul(int keyCode, KeyEvent event)
  {
    boolean shift = (event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0 || event.isShiftPressed();
    int row = -1, col = -1;
    switch (keyCode)
    {
      case KeyEvent.KEYCODE_Q: row=0; col=0; break;
      case KeyEvent.KEYCODE_W: row=0; col=1; break;
      case KeyEvent.KEYCODE_E: row=0; col=2; break;
      case KeyEvent.KEYCODE_R: row=0; col=3; break;
      case KeyEvent.KEYCODE_T: row=0; col=4; break;
      case KeyEvent.KEYCODE_Y: row=0; col=5; break;
      case KeyEvent.KEYCODE_U: row=0; col=6; break;
      case KeyEvent.KEYCODE_I: row=0; col=7; break;
      case KeyEvent.KEYCODE_O: row=0; col=8; break;
      case KeyEvent.KEYCODE_P: row=0; col=9; break;
      case KeyEvent.KEYCODE_A: row=1; col=0; break;
      case KeyEvent.KEYCODE_S: row=1; col=1; break;
      case KeyEvent.KEYCODE_D: row=1; col=2; break;
      case KeyEvent.KEYCODE_F: row=1; col=3; break;
      case KeyEvent.KEYCODE_G: row=1; col=4; break;
      case KeyEvent.KEYCODE_H: row=1; col=5; break;
      case KeyEvent.KEYCODE_J: row=1; col=6; break;
      case KeyEvent.KEYCODE_K: row=1; col=7; break;
      case KeyEvent.KEYCODE_L: row=1; col=8; break;
      case KeyEvent.KEYCODE_Z: row=2; col=1; break;
      case KeyEvent.KEYCODE_X: row=2; col=2; break;
      case KeyEvent.KEYCODE_C: row=2; col=3; break;
      case KeyEvent.KEYCODE_V: row=2; col=4; break;
      case KeyEvent.KEYCODE_B: row=2; col=5; break;
      case KeyEvent.KEYCODE_N: row=2; col=6; break;
      case KeyEvent.KEYCODE_M: row=2; col=7; break;
      default: return 0;
    }
    // number row / bottom row가 추가된 modified 레이아웃은 row가 1씩 밀리므로 unmodified 기준로 매핑
    KeyboardData kd = current_layout_unmodified();
    if (row >= kd.rows.size()) return fallbackDubeolsik(keyCode, shift);
    KeyboardData.Row r = kd.rows.get(row);
    if (col >= r.keys.size()) return fallbackDubeolsik(keyCode, shift);
    KeyboardData.Key k = r.keys.get(col);
    KeyValue kv = k.keys[0];
    if (kv == null || kv.getKind() != KeyValue.Kind.Char) return fallbackDubeolsik(keyCode, shift);
    char c = kv.getChar();
    if (!HangulComposer.is_jamo(c)) return fallbackDubeolsik(keyCode, shift);
    if (shift) c = shiftToDouble(c);
    return c;
  }

  private char fallbackDubeolsik(int keyCode, boolean shift)
  {
    char c = 0;
    switch (keyCode)
    {
      case KeyEvent.KEYCODE_Q: c='ㅂ'; break;
      case KeyEvent.KEYCODE_W: c='ㅈ'; break;
      case KeyEvent.KEYCODE_E: c='ㄷ'; break;
      case KeyEvent.KEYCODE_R: c='ㄱ'; break;
      case KeyEvent.KEYCODE_T: c='ㅅ'; break;
      case KeyEvent.KEYCODE_Y: c='ㅛ'; break;
      case KeyEvent.KEYCODE_U: c='ㅕ'; break;
      case KeyEvent.KEYCODE_I: c='ㅑ'; break;
      case KeyEvent.KEYCODE_O: c='ㅐ'; break;
      case KeyEvent.KEYCODE_P: c='ㅔ'; break;
      case KeyEvent.KEYCODE_A: c='ㅁ'; break;
      case KeyEvent.KEYCODE_S: c='ㄴ'; break;
      case KeyEvent.KEYCODE_D: c='ㅇ'; break;
      case KeyEvent.KEYCODE_F: c='ㄹ'; break;
      case KeyEvent.KEYCODE_G: c='ㅎ'; break;
      case KeyEvent.KEYCODE_H: c='ㅗ'; break;
      case KeyEvent.KEYCODE_J: c='ㅓ'; break;
      case KeyEvent.KEYCODE_K: c='ㅏ'; break;
      case KeyEvent.KEYCODE_L: c='ㅣ'; break;
      case KeyEvent.KEYCODE_Z: c='ㅋ'; break;
      case KeyEvent.KEYCODE_X: c='ㅌ'; break;
      case KeyEvent.KEYCODE_C: c='ㅊ'; break;
      case KeyEvent.KEYCODE_V: c='ㅍ'; break;
      case KeyEvent.KEYCODE_B: c='ㅠ'; break;
      case KeyEvent.KEYCODE_N: c='ㅜ'; break;
      case KeyEvent.KEYCODE_M: c='ㅡ'; break;
      default: return 0;
    }
    if (shift) c = shiftToDouble(c);
    return c;
  }

  private char shiftToDouble(char c)
  {
    switch (c)
    {
      case 'ㅂ': return 'ㅃ';
      case 'ㅈ': return 'ㅉ';
      case 'ㄷ': return 'ㄸ';
      case 'ㄱ': return 'ㄲ';
      case 'ㅅ': return 'ㅆ';
      case 'ㅐ': return 'ㅒ';
      case 'ㅔ': return 'ㅖ';
      default: return c;
    }
  }



  public void launch_dictionaries_activity()
  {
    start_activity(DictionariesActivity.class);
  }

  /** Called from [onClick] attributes. */
  public void launch_dictionaries_activity(View v)
  {
    launch_dictionaries_activity();
  }

  void start_activity(Class cls)
  {
    Intent intent = new Intent(this, cls);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity(intent);
  }

  /** Not static */
  public class Receiver implements KeyEventHandler.IReceiver,
         KeyValue.Stateful.Symbol_provider, DictionarySwitcher.Callback
  {
    public void handle_event_key(KeyValue.Event ev)
    {
      switch (ev)
      {
        case CONFIG:
          start_activity(SettingsActivity.class);
          break;

        case SWITCH_TEXT:
          _currentSpecialLayout = null;
          set_layout_view(current_layout());
          break;

        case SWITCH_NUMERIC:
          setSpecialLayout(loadNumericLayout());
          break;

        case SWITCH_EMOJI:
          if (_emojiPane == null)
            _emojiPane = (ViewGroup)inflate_view(R.layout.emoji_pane);
          setInputView(_emojiPane);
          break;

        case SWITCH_CLIPBOARD:
          if (_clipboard_pane == null)
            _clipboard_pane = (ViewGroup)inflate_view(R.layout.clipboard_pane);
          setInputView(_clipboard_pane);
          break;

        case SWITCH_BACK_EMOJI:
        case SWITCH_BACK_CLIPBOARD:
          setInputView(_keyboard_container_view);
          break;

        case CHANGE_METHOD_PICKER:
          get_imm().showInputMethodPicker();
          break;

        case CHANGE_METHOD_PREV:
          if (VERSION.SDK_INT < 28)
            get_imm().switchToLastInputMethod(getConnectionToken());
          else
            switchToPreviousInputMethod();
          break;

        case CHANGE_METHOD_NEXT:
          if (VERSION.SDK_INT < 28)
            get_imm().switchToNextInputMethod(getConnectionToken(), false);
          else
            switchToNextInputMethod(false);
          break;

        case ACTION:
          InputConnection conn = getCurrentInputConnection();
          if (conn != null)
            conn.performEditorAction(_config.editor_config.actionId);
          break;

        case SWITCH_FORWARD:
          incrTextLayout(1);
          break;

        case SWITCH_BACKWARD:
          incrTextLayout(-1);
          break;

        case SWITCH_GREEKMATH:
          setSpecialLayout(loadNumpad(R.xml.greekmath));
          break;

        case CAPS_LOCK:
          set_shift_state(true, true);
          break;

        case SWITCH_VOICE_TYPING:
          if (!VoiceImeSwitcher.switch_to_voice_ime(Keyboard2.this, get_imm(),
                Config.globalPrefs()))
            _config.shouldOfferVoiceTyping = false;
          break;

        case SWITCH_VOICE_TYPING_CHOOSER:
          VoiceImeSwitcher.choose_voice_ime(Keyboard2.this, get_imm(),
              Config.globalPrefs());
          break;

        case HIDE_SELF:
          Keyboard2.this.requestHideSelf(0);
          break;

        case CHANGE_DICTIONARY:
          new DictionarySwitcher(Keyboard2.this, _dictionaries, this).choose();
          break;
      }
    }

    public void set_shift_state(boolean state, boolean lock)
    {
      _keyboard_layout_view.set_shift_state(state, lock);
    }

    public void set_compose_pending(boolean pending)
    {
      _keyboard_layout_view.set_compose_pending(pending);
    }

    public void selection_state_changed(boolean selection_is_ongoing)
    {
      _keyboard_layout_view.set_selection_state(selection_is_ongoing);
    }

    public InputConnection getCurrentInputConnection()
    {
      return Keyboard2.this.getCurrentInputConnection();
    }

    public Handler getHandler()
    {
      return _handler;
    }

    public void set_suggestions(Suggestions suggestions)
    {
      _candidates_view.set_candidates(suggestions);
    }

    public String provide_stateful_key_symbol(KeyValue.Stateful q)
    {
      switch (q)
      {
        case Complete_first: return _suggestions.suggestions[0];
        case Complete_second: return _suggestions.suggestions[1];
        case Complete_third: return _suggestions.suggestions[2];
        case Complete_emoji: return _suggestions.emoji_suggestion;
      }
      return "";
    }

    public void on_change_dictionary(String dict_name)
    {
      select_dictionary(dict_name);
    }

    public void launch_dictionaries_activity()
    {
      Keyboard2.this.launch_dictionaries_activity();
    }
  }

  private IBinder getConnectionToken()
  {
    return getWindow().getWindow().getAttributes().token;
  }

  private View inflate_view(int layout)
  {
    return View.inflate(new ContextThemeWrapper(this, _config.theme), layout, null);
  }
}
