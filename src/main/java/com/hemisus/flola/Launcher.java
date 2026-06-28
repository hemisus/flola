package com.hemisus.flola;

/**
 * fat jar(단일 jar) 실행용 런처.
 *
 * <p>JavaFX 앱의 메인 클래스(Application 상속)를 jar의 Main-Class로 직접 지정하면
 * 모듈 시스템 때문에 "JavaFX runtime components are missing" 오류가 난다.
 * Application을 <b>상속하지 않는</b> 이 런처를 Main-Class로 두고 여기서
 * 실제 앱({@link MainApp})을 호출하면 classpath 모드로 정상 실행된다.</p>
 */
public class Launcher {
    public static void main(String[] args) {
        MainApp.main(args);   // MainApp.main()이 Application.launch(args)를 호출한다고 가정
    }
}