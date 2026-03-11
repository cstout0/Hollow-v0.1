package noctfield;

import noctfield.core.GameApp;

public class Main {
    public static void main(String[] args) {
        GameApp app = new GameApp();
        for (String a : args) {
            if (a.equals("--builder")) app.enableBuilderMode();
        }
        app.run();
    }
}
