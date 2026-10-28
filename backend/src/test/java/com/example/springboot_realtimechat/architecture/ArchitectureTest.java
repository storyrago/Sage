package com.example.springboot_realtimechat.architecture;

import com.example.springboot_realtimechat.domain.member.service.MemberService;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchIgnore;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;

/**
 * 도메인 사이 의존은 위에서 아래로만 흐른다.
 * 이미 있는 위반은 archunit_store에 얼려 두고 새 위반만 막는다. 고친 위반은 저장소에서 자동으로 빠진다.
 */
@AnalyzeClasses(packages = ArchitectureTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final String ROOT = "com.example.springboot_realtimechat";

    // 순환 위반은 순환에 걸린 의존 전체가 한 덩어리라, 의존 하나만 줄어도 얼린 위반과 달라져 새 위반으로 잡힌다.
    // 그래서 얼리지 않는다. 순환은 모두 계층 위반이기도 해서 그동안은 아래 계층 규칙이 막는다. 순환이 0이 되면 켠다.
    @ArchIgnore(reason = "도메인 순환이 남아 있다. 0이 되면 켠다")
    @ArchTest
    static final ArchRule 도메인_사이에_순환이_없다 =
            slices().matching(ROOT + ".domain.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule 도메인_의존은_위에서_아래로만_흐른다 = freeze(
            layeredArchitecture().consideringOnlyDependenciesInLayers()
                    .optionalLayer("application").definedBy(ROOT + ".application..")
                    .layer("auth").definedBy(ROOT + ".domain.auth..")
                    .layer("message").definedBy(ROOT + ".domain.message..")
                    .layer("presence").definedBy(ROOT + ".domain.presence..")
                    .layer("chatroom").definedBy(ROOT + ".domain.chatroom..")
                    .layer("member").definedBy(ROOT + ".domain.member..")
                    .layer("image").definedBy(ROOT + ".domain.image..")
                    .whereLayer("application").mayNotBeAccessedByAnyLayer()
                    .whereLayer("auth").mayOnlyBeAccessedByLayers("application")
                    .whereLayer("message").mayOnlyBeAccessedByLayers("application", "auth")
                    .whereLayer("presence").mayOnlyBeAccessedByLayers("application", "auth", "message")
                    .whereLayer("chatroom").mayOnlyBeAccessedByLayers("application", "auth", "message", "presence")
                    .whereLayer("member").mayOnlyBeAccessedByLayers(
                            "application", "auth", "message", "presence", "chatroom")
                    .whereLayer("image").mayOnlyBeAccessedByLayers(
                            "application", "auth", "message", "presence", "chatroom", "member"));

    @ArchTest
    static final ArchRule global은_domain을_모른다 = freeze(
            noClasses().that().resideInAPackage(ROOT + ".global..")
                    .should().dependOnClassesThat().resideInAPackage(ROOT + ".domain.."));

    @ArchTest
    static final ArchRule 회원_도메인은_채팅방과_메시지를_모른다 =
            noClasses().that().resideInAPackage(ROOT + ".domain.member..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(ROOT + ".domain.chatroom..", ROOT + ".domain.message..");

    @ArchTest
    static final ArchRule 회원_행_삭제는_탈퇴_유스케이스만_부른다 =
            noClasses().that().resideOutsideOfPackage(ROOT + ".application.account..")
                    .should().callMethod(MemberService.class, "deleteRecord", Long.class);
}
