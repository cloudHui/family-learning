package cc.ccwu.familylearning.model;

public class PrintableQuestion {
    public String id;
    public String type;
    public String text;
    public String answer;
    public PrintableQuestion(String id, String type, String text, String answer) {
        this.id = id; this.type = type; this.text = text; this.answer = answer;
    }
}
