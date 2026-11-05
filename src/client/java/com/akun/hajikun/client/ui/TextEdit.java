package com.akun.hajikun.client.ui;

/**
 * 虚拟机内所有文本框共用的编辑状态：光标、选区与剪贴板操作。
 *
 * <p>只负责纯文本逻辑；像素定位（点击落在第几个字符）由调用方按当前
 * 字体宽度换算成字符索引后调用 {@link #click(int, boolean)} / {@link #dragTo(int)}。</p>
 */
public final class TextEdit {
    /** 当前文本。 */
    public String text = "";
    /** 光标位置，0..text.length()。 */
    public int caret;
    /** 选区锚点；与 caret 相同表示没有选区。 */
    public int anchor;

    public void set(String value) {
        this.text = value == null ? "" : value;
        this.caret = this.text.length();
        this.anchor = this.caret;
    }

    public void clear() {
        this.set("");
    }

    public boolean hasSelection() {
        return this.caret != this.anchor;
    }

    public int selStart() {
        return Math.min(this.caret, this.anchor);
    }

    public int selEnd() {
        return Math.max(this.caret, this.anchor);
    }

    public String selection() {
        return this.hasSelection() ? this.text.substring(this.selStart(), this.selEnd()) : "";
    }

    public void selectAll() {
        this.anchor = 0;
        this.caret = this.text.length();
    }

    public void click(int index, boolean extend) {
        int at = Math.max(0, Math.min(this.text.length(), index));
        if (!extend) {
            this.anchor = at;
        }
        this.caret = at;
    }

    public void dragTo(int index) {
        this.caret = Math.max(0, Math.min(this.text.length(), index));
    }

    public void replaceSelection(String value, int maxLength) {
        int start = this.selStart();
        int end = this.selEnd();
        String next = this.text.substring(0, start) + value + this.text.substring(end);
        if (maxLength > 0 && next.length() > maxLength) {
            next = next.substring(0, maxLength);
        }
        this.text = next;
        this.caret = Math.min(next.length(), start + value.length());
        this.anchor = this.caret;
    }

    public void insert(String value, int maxLength) {
        this.replaceSelection(value, maxLength);
    }

    public void backspace() {
        if (this.hasSelection()) {
            this.replaceSelection("", 0);
            return;
        }
        if (this.caret <= 0) {
            return;
        }
        this.text = this.text.substring(0, this.caret - 1) + this.text.substring(this.caret);
        this.caret--;
        this.anchor = this.caret;
    }

    public void deleteForward() {
        if (this.hasSelection()) {
            this.replaceSelection("", 0);
            return;
        }
        if (this.caret >= this.text.length()) {
            return;
        }
        this.text = this.text.substring(0, this.caret) + this.text.substring(this.caret + 1);
        this.anchor = this.caret;
    }

    /** 左右移动；extend 为 true 时保持锚点形成选区。 */
    public void move(int delta, boolean extend) {
        int next = Math.max(0, Math.min(this.text.length(), this.caret + delta));
        this.caret = next;
        if (!extend) {
            this.anchor = next;
        }
    }

    public void wordMove(int direction, boolean extend) {
        int at = this.caret;
        if (direction < 0) {
            while (at > 0 && Character.isWhitespace(this.text.charAt(at - 1))) {
                at--;
            }
            while (at > 0 && !Character.isWhitespace(this.text.charAt(at - 1))) {
                at--;
            }
        } else {
            while (at < this.text.length() && !Character.isWhitespace(this.text.charAt(at))) {
                at++;
            }
            while (at < this.text.length() && Character.isWhitespace(this.text.charAt(at))) {
                at++;
            }
        }
        this.caret = at;
        if (!extend) {
            this.anchor = at;
        }
    }

    public void home(boolean extend) {
        int at = this.text.lastIndexOf('\n', Math.max(0, this.caret - 1)) + 1;
        this.caret = at;
        if (!extend) {
            this.anchor = at;
        }
    }

    public void lineEnd(boolean extend) {
        int at = this.text.indexOf('\n', this.caret);
        if (at < 0) {
            at = this.text.length();
        }
        this.caret = at;
        if (!extend) {
            this.anchor = at;
        }
    }

    /** 多行文本框的上下移动：保持列号，限制在目标行长度内。 */
    public void moveLine(int direction, boolean extend) {
        int lineStart = this.text.lastIndexOf('\n', Math.max(0, this.caret - 1)) + 1;
        int column = this.caret - lineStart;
        if (direction < 0) {
            if (lineStart == 0) {
                return;
            }
            int prevEnd = lineStart - 1;
            int prevStart = this.text.lastIndexOf('\n', Math.max(0, prevEnd - 1)) + 1;
            this.caret = Math.min(prevStart + column, prevEnd);
        } else {
            int lineEnd = this.text.indexOf('\n', this.caret);
            if (lineEnd < 0) {
                return;
            }
            int nextStart = lineEnd + 1;
            int nextEnd = this.text.indexOf('\n', nextStart);
            if (nextEnd < 0) {
                nextEnd = this.text.length();
            }
            this.caret = Math.min(nextStart + column, nextEnd);
        }
        if (!extend) {
            this.anchor = this.caret;
        }
    }

    /** 输入前把换行压成空格，单行字段使用。 */
    public static String singleLine(String value) {
        return value == null ? "" : value.replace("\r", "").replace('\n', ' ');
    }
}
