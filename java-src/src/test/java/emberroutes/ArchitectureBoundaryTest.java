package emberroutes;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureBoundaryTest {
    private static ArchRule containerPatchRule(String rootClass) {
	return noClasses().that().haveNameMatching(java.util.regex.Pattern.quote(rootClass) + "(\\$.*)?")
		.should().dependOnClassesThat().resideInAPackage("se.krka.kahlua..");
    }

    private static void checkPatch(JavaClasses classes, String rootClass) {
	assertTrue(classes.stream().anyMatch(c -> c.getName().equals(rootClass)),
		"Container patch class was not imported: " + rootClass);
	containerPatchRule(rootClass).check(classes);
    }

    @Test
    void productionContainerPatchesDoNotDirectlyDependOnKahlua() {
	JavaClasses classes = new ClassFileImporter().importPath(Path.of(System.getProperty("emberroutes.mainClasses")));
	checkPatch(classes, "emberroutes.Patch_ItemContainer");
	checkPatch(classes, "emberroutes.Patch_GameWindowSave");
	checkPatch(classes, "emberroutes.Patch_IngameExit");
	checkPatch(classes, "emberroutes.Patch_IsoChunk");
	checkPatch(classes, "emberroutes.Patch_WorldRender");
	checkPatch(classes, "emberroutes.Patch_BedRenderElevation");
    }

    @Test
    void npcDomainDoesNotDependOnBridgeOrPatches() {
	JavaClasses classes = new ClassFileImporter().importPath(Path.of(System.getProperty("emberroutes.mainClasses")));
	noClasses().that().resideInAPackage("emberroutes.npc..")
		.should().dependOnClassesThat().haveNameMatching("emberroutes\\.(EmberRoutesBridge|Patch_.*)")
		.check(classes);
    }

    @Test
    void warehouseDomainDoesNotDependOnNpcOrBridgeOrPatches() {
	JavaClasses classes = new ClassFileImporter().importPath(Path.of(System.getProperty("emberroutes.mainClasses")));
	noClasses().that().resideInAPackage("emberroutes.warehouse..")
		.should().dependOnClassesThat().resideInAPackage("emberroutes.npc..")
		.check(classes);
    }

    @Test
    void directKahluaDependencyIsDetected() {
	JavaClasses classes = new ClassFileImporter().importClasses(
		architecturefixture.Patch_ItemContainer.class,
		architecturefixture.Patch_ItemContainer.Callback.class);
	assertThrows(AssertionError.class, () -> checkPatch(classes, "architecturefixture.Patch_ItemContainer"));
    }

    @Test
    void missingPatchClassDoesNotPassSilently() {
	JavaClasses classes = new ClassFileImporter().importClasses(architecturefixture.Patch_ItemContainer.class);
	assertThrows(AssertionError.class, () -> checkPatch(classes, "missing.Patch_ItemContainer"));
    }
}
