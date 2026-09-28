package com.smarthealthfinance.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

/**
 * Guarda da arquitetura aprovada (specs 05.3, ADR-0001):
 * Presentation → Application → Domain; Infrastructure implementa portas internas.
 */
@AnalyzeClasses(packages = "com.smarthealthfinance", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	@ArchTest
	static final ArchRule domainIsFrameworkAndLayerFree = noClasses().that()
		.resideInAPackage("..domain..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("org.springframework..", "jakarta.persistence..", "..application..",
				"..infrastructure..", "..presentation..")
		.allowEmptyShould(true)
		.because("o domínio é o núcleo determinístico e não conhece frameworks nem camadas externas");

	@ArchTest
	static final ArchRule applicationDoesNotDependOnOuterLayers = noClasses().that()
		.resideInAPackage("..application..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("..infrastructure..", "..presentation..")
		.allowEmptyShould(true)
		.because("a Application define portas; a Infrastructure as implementa");

	@ArchTest
	static final ArchRule presentationDoesNotDependOnInfrastructure = noClasses().that()
		.resideInAPackage("..presentation..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("..infrastructure..")
		.allowEmptyShould(true)
		.because("a API fala com casos de uso, nunca diretamente com adapters/persistência");

	@ArchTest
	static final ArchRule controllersLiveInPresentation = classes().that()
		.areAnnotatedWith(RestController.class)
		.should()
		.resideInAPackage("..presentation..")
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule modulesAreFreeOfCycles = slices().matching("com.smarthealthfinance.(*)..")
		.should()
		.beFreeOfCycles()
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule domainNeverUsesFloatingPoint = noFields().that()
		.areDeclaredInClassesThat()
		.resideInAPackage("..domain..")
		.should()
		.haveRawType(float.class)
		.orShould()
		.haveRawType(double.class)
		.orShould()
		.haveRawType(Float.class)
		.orShould()
		.haveRawType(Double.class)
		.allowEmptyShould(true)
		.because("dinheiro e valores financeiros nunca usam float/double");

}
