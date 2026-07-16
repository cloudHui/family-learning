package cc.ccwu.familylearning.model;

public class MathQuestion {
    public String id;
    public int left;
    public int right;
    public String operator;
    public String text;
    public int answer;

    public MathQuestion(String id, int left, int right, String operator, int answer) {
        this.id = id;
        this.left = left;
        this.right = right;
        this.operator = operator;
        this.text = left + " " + operator + " " + right + " = ?";
        this.answer = answer;
    }
}
