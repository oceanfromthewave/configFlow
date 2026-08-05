/*
 * bootstrap: Spring Boot assembly + REST/SSE API.
 * The only module that may depend on Spring and on every other module.
 */
plugins {
    java
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))

    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":infrastructure:vcs-git"))
    implementation(project(":infrastructure:vcs-svn"))
    implementation(project(":infrastructure:persistence"))
    implementation(project(":infrastructure:credential"))
    implementation(project(":infrastructure:ai-providers"))

    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4는 슬라이스 테스트를 모듈별로 분리했다. @WebMvcTest / MockMvc는
    // webmvc-test 스타터에, TestRestTemplate은 resttestclient 모듈에 들어 있다.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    // TestRestTemplate 자동 설정이 RestTemplateBuilder를 요구하므로 restclient도 필요하다.
    testImplementation("org.springframework.boot:spring-boot-restclient")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
}
