package noctfield.core;

import org.joml.Vector2f;

import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.glfw.GLFW.*;

public class InputState {
    private final long window;
    private final Set<Integer> pressed = new HashSet<>();
    private final Set<Integer> down = new HashSet<>();
    private final Set<Integer> mousePressed = new HashSet<>();
    private final Set<Integer> mouseDown = new HashSet<>();

    private double mouseX;
    private double mouseY;
    private double lastMouseX;
    private double lastMouseY;
    private double scrollAccum = 0.0; // M53: accumulated scroll delta
    private final StringBuilder typedChars = new StringBuilder();

    public InputState(long window) {
        this.window = window;

        glfwSetScrollCallback(window, (w, xOff, yOff) -> scrollAccum += yOff); // M53
        glfwSetCharCallback(window, (w, codepoint) -> { if (codepoint >= 32 && codepoint < 127) typedChars.append((char)codepoint); });

        glfwSetKeyCallback(window, (w, key, sc, action, mods) -> {
            if (key < 0) return;
            if (action == GLFW_PRESS) {
                down.add(key);
                pressed.add(key);
            } else if (action == GLFW_RELEASE) {
                down.remove(key);
            }
        });

        glfwSetCursorPosCallback(window, (w, x, y) -> {
            mouseX = x;
            mouseY = y;
        });

        glfwSetMouseButtonCallback(window, (w, button, action, mods) -> {
            if (button < 0) return;
            if (action == GLFW_PRESS) {
                mouseDown.add(button);
                mousePressed.add(button);
            } else if (action == GLFW_RELEASE) {
                mouseDown.remove(button);
            }
        });

        recenterMouse();
        lastMouseX = mouseX;
        lastMouseY = mouseY;
    }

    public void beginFrame() {
        // pressed is consumed per-frame
    }

    public void endFrame() {
        pressed.clear();
        mousePressed.clear();
        lastMouseX = mouseX;
        lastMouseY = mouseY;
    }

    public boolean isDown(int key) {
        return down.contains(key);
    }

    public boolean wasPressed(int key) {
        return pressed.contains(key);
    }

    public boolean isMouseDown(int button) { return mouseDown.contains(button); }
    public boolean wasMousePressed(int button) { return mousePressed.contains(button); }

    public Vector2f mouseDelta() {
        return new Vector2f((float) (mouseX - lastMouseX), (float) (mouseY - lastMouseY));
    }

    public double mouseX() { return mouseX; }
    public double mouseY() { return mouseY; }

    /** M53: returns accumulated scroll since last call, then resets. Positive = scroll up. */
    public int consumeScroll() {
        int s = (int)scrollAccum;
        scrollAccum -= s;
        return s;
    }

    /** Returns all typed printable characters since last call and clears the buffer. */
    public String consumeTyped() {
        String s = typedChars.toString();
        typedChars.setLength(0);
        return s;
    }

    public void recenterMouse() {
        int[] w = new int[1];
        int[] h = new int[1];
        glfwGetWindowSize(window, w, h);
        double cx = w[0] * 0.5;
        double cy = h[0] * 0.5;
        glfwSetCursorPos(window, cx, cy);
        mouseX = cx;
        mouseY = cy;
        lastMouseX = cx;
        lastMouseY = cy;
    }
}
