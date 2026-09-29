package com.wargame.model.constants;

import java.text.Normalizer;

/** 游戏内展示名可用标点、符号和普通 emoji；拒绝不可见或可改变文字方向的字符。 */
public final class DisplayNamePolicy {
    private DisplayNamePolicy() {}

    /**
     * 规范化游戏内展示名，按 Unicode 码点计数并拒绝控制、格式和不可见分隔字符。
     * @return 可直接存储的名称；非法输入抛出 IllegalArgumentException
     */
    public static String validate(String rawName, int maxCodePoints, String label) {
        if (rawName == null) throw new IllegalArgumentException(label + "不能为空");
        // 仅剔除首尾普通空格；换行、制表符等必须进入下方校验，不能被 trim 悄悄删除。
        String name = Normalizer.normalize(rawName, Normalizer.Form.NFC).replaceAll("^ +| +$", "");
        if (name.isEmpty()) throw new IllegalArgumentException(label + "不能为空");
        if (name.codePointCount(0, name.length()) > maxCodePoints) {
            throw new IllegalArgumentException(label + "最多" + maxCodePoints + "个字符");
        }
        for (int i = 0; i < name.length();) {
            int cp = name.codePointAt(i);
            int type = Character.getType(cp);
            // 保留普通空格；控制符、方向控制符和其他不可见分隔符会造成伪装及排版错乱。
            if (cp != ' ' && (Character.isWhitespace(cp) || Character.isSpaceChar(cp))) {
                throw new IllegalArgumentException(label + "不能包含换行或不可见字符");
            }
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.UNASSIGNED || type == Character.PRIVATE_USE) {
                throw new IllegalArgumentException(label + "不能包含换行或不可见字符");
            }
            i += Character.charCount(cp);
        }
        return name;
    }
}
