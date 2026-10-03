package dev.theredja.src2mc.logic;

/**
 * One output's connection, Source's {@code CEventAction}: what it fires, after how long, and how
 * many more times. {@code parameter} is null when the connection names none, so the output's own
 * value goes along instead.
 */
final class Connection {
    final String target, input, parameter;
    final double delay;
    /** -1 for always. */
    int timesLeft;

    Connection(String target, String input, String parameter, double delay, int timesLeft) {
        this.target = target;
        this.input = input;
        this.parameter = parameter == null || parameter.isEmpty() ? null : parameter;
        this.delay = delay;
        this.timesLeft = timesLeft;
    }

    /**
     * {@code AddOutput}'s {@code "target:input:parameter:delay:times"}, or the keyvalue form with
     * {@code ESC} or comma separators; null when it does not have five fields.
     */
    static Connection parse(String text) {
        String separator = text.indexOf('\u001b') >= 0 ? "\u001b" : text.indexOf(':') >= 0 ? ":" : ",";
        String[] fields = text.split(java.util.regex.Pattern.quote(separator), -1);
        if (fields.length < 5) return null;
        double delay = Math.max(0, Variant.number(fields[3]));
        int times = Variant.integer(fields[4]);
        return new Connection(fields[0].trim(), fields[1].trim(), fields[2], delay, times <= 0 ? -1 : times);
    }
}
