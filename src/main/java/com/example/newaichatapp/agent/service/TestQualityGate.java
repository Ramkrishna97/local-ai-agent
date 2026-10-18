package com.example.newaichatapp.agent.service;

import com.example.newaichatapp.agent.model.PlanStep;
import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

@Component
public class TestQualityGate {
    public void validate(PlanStep step) {
        requireClass(step.className, "Implementation");
        requireClass(step.testClassName, "Test");
        if (step.className.equals(step.testClassName)) throw new IllegalArgumentException("Class names must differ");
        if (step.generatedCode == null || step.generatedCode.isBlank() || step.testCode == null || step.testCode.isBlank())
            throw new IllegalArgumentException("Missing implementation or test source");
        if (!Pattern.compile("\\b" + Pattern.quote(step.className) + "\\b").matcher(step.testCode).find())
            throw new IllegalArgumentException("Test does not reference implementation");
        if (!step.testCode.contains("main(")) throw new IllegalArgumentException("Test needs a main method");
        if (!step.testCode.contains("throw new AssertionError"))
            throw new IllegalArgumentException("Test must contain executable assertions");
    }
    private void requireClass(String name, String label) {
        if (name == null || !name.matches("[A-Za-z_$][A-Za-z0-9_$]*"))
            throw new IllegalArgumentException(label + " class name invalid");
    }
}
