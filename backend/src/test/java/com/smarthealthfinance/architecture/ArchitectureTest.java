package com.smarthealthfinance.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Guarda da arquitetura aprovada (specs 05.3, ADR-0001):
 * Presentation → Application → Domain; Infrastructure implementa portas internas.
 */
@AnalyzeClasses(packages = "com.smarthealthfinance", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	private static final String SHARED = "com.smarthealthfinance.shared..";

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

	// Convenção de subpacotes dentro das camadas de cada módulo (ADR-0001).

	@ArchTest
	static final ArchRule controllersLiveInPresentationController = classes().that()
		.areAnnotatedWith(RestController.class)
		.should()
		.resideInAPackage("..presentation.controller..")
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule moduleExceptionHandlersLiveInPresentationHandler = classes().that()
		.areAnnotatedWith(RestControllerAdvice.class)
		.and()
		.resideOutsideOfPackage(SHARED)
		.should()
		.resideInAPackage("..presentation.handler..")
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule applicationBeansAreUseCasesOrServices = classes().that()
		.areAnnotatedWith(Service.class)
		.should()
		.resideInAnyPackage("..application.usecase..", "..application.service..")
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule domainEnumsLiveInEnums = classes().that()
		.areEnums()
		.and()
		.resideInAPackage("..domain..")
		.should()
		.resideInAPackage("..domain.enums..")
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule moduleExceptionsLiveInExceptionPackages = classes().that()
		.areAssignableTo(RuntimeException.class)
		.and()
		.resideInAnyPackage("..domain..", "..application..")
		.and()
		.resideOutsideOfPackage(SHARED)
		.should()
		.resideInAnyPackage("..domain.exception..", "..application.exception..")
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule jpaMappingsLiveInPersistenceEntity = classes().that()
		.areAnnotatedWith(Entity.class)
		.or()
		.areAnnotatedWith(Embeddable.class)
		.should()
		.resideInAPackage("..infrastructure.persistence.entity..")
		.allowEmptyShould(true)
		.because("entidades JPA são detalhe de persistência, distintas das entidades de domínio (domain.model)");

	@ArchTest
	static final ArchRule springDataRepositoriesLiveInPersistenceRepository = classes().that()
		.areAssignableTo(Repository.class)
		.should()
		.resideInAPackage("..infrastructure.persistence.repository..")
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule modulesAreFreeOfCycles = slices().matching("com.smarthealthfinance.(*)..")
		.should()
		.beFreeOfCycles()
		.allowEmptyShould(true);

	// Direção entre módulos do First Financial Loop (ADR-0005): Transactions → Accounts → Identity.
	// O saldo (M3) é derivado de transações; Accounts nunca conhece Transactions.

	@ArchTest
	static final ArchRule accountsDoNotDependOnTransactions = noClasses().that()
		.resideInAPackage("com.smarthealthfinance.accounts..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("com.smarthealthfinance.transactions..")
		.allowEmptyShould(true)
		.because("Accounts é upstream de Transactions; o inverso criaria acoplamento circular de domínio");

	// Overview (ADR-0006) é read model downstream: Identity → Accounts → Transactions → Overview.

	@ArchTest
	static final ArchRule coreModulesDoNotDependOnOverview = noClasses().that()
		.resideInAnyPackage("com.smarthealthfinance.identity..", "com.smarthealthfinance.accounts..",
				"com.smarthealthfinance.transactions..", "com.smarthealthfinance.shared..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("com.smarthealthfinance.overview..")
		.allowEmptyShould(true)
		.because("Overview só consome o Financial Core; nada do núcleo pode depender de uma visão de leitura");

	@ArchTest
	static final ArchRule overviewOnlyReadsThroughPortsAndNeverWritesTheCore = noClasses().that()
		.resideInAPackage("com.smarthealthfinance.overview..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.smarthealthfinance.transactions.application.usecase..",
				"com.smarthealthfinance.accounts.application.usecase..",
				"com.smarthealthfinance.transactions.infrastructure..",
				"com.smarthealthfinance.accounts.infrastructure..")
		.allowEmptyShould(true)
		.because("o Overview não executa comandos nem toca nos adapters de Accounts/Transactions: só lê (ADR-0006)");

	// Ingestion (ADR-0009) é downstream: identity → accounts → transactions → ingestion.

	@ArchTest
	static final ArchRule coreModulesDoNotDependOnIngestion = noClasses().that()
		.resideInAnyPackage("com.smarthealthfinance.identity..", "com.smarthealthfinance.accounts..",
				"com.smarthealthfinance.transactions..", "com.smarthealthfinance.overview..",
				"com.smarthealthfinance.shared..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("com.smarthealthfinance.ingestion..")
		.allowEmptyShould(true)
		.because("importação alimenta o Financial Core; o núcleo nunca conhece de onde o dado veio");

	@ArchTest
	static final ArchRule ingestionNeverTouchesCoreAdapters = noClasses().that()
		.resideInAPackage("com.smarthealthfinance.ingestion..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.smarthealthfinance.transactions.infrastructure..",
				"com.smarthealthfinance.accounts.infrastructure..")
		.allowEmptyShould(true)
		.because("Ingestion cria transações só pelo caso de uso de Transactions, nunca escrevendo na tabela (ADR-0009)");

	// Analytics (ADR-0014) é read model downstream de tudo: lê Transactions/Accounts e consome eventos pelo nome.

	@ArchTest
	static final ArchRule nothingDependsOnAnalytics = noClasses().that()
		.resideInAnyPackage("com.smarthealthfinance.identity..", "com.smarthealthfinance.accounts..",
				"com.smarthealthfinance.transactions..", "com.smarthealthfinance.overview..",
				"com.smarthealthfinance.ingestion..", "com.smarthealthfinance.shared..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("com.smarthealthfinance.analytics..")
		.allowEmptyShould(true)
		.because("Analytics é derivado; o núcleo e o Overview nunca dependem dele sem decisão registrada (ADR-0014)");

	@ArchTest
	static final ArchRule analyticsOnlyReadsTheCore = noClasses().that()
		.resideInAPackage("com.smarthealthfinance.analytics..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.smarthealthfinance.transactions.application..",
				"com.smarthealthfinance.transactions.infrastructure..",
				"com.smarthealthfinance.accounts.application..", "com.smarthealthfinance.accounts.infrastructure..",
				"com.smarthealthfinance.ingestion..", "com.smarthealthfinance.overview..")
		.allowEmptyShould(true)
		.because("o Analytics não executa comandos: lê a fonte da verdade e reage a eventos pelo nome (ADR-0014)");

	// Health (ADR-0017/0018) é read model downstream do Analytics e do Overview: lê só portas e modelos de domínio.

	@ArchTest
	static final ArchRule nothingDependsOnHealth = noClasses().that()
		.resideInAnyPackage("com.smarthealthfinance.identity..", "com.smarthealthfinance.accounts..",
				"com.smarthealthfinance.transactions..", "com.smarthealthfinance.overview..",
				"com.smarthealthfinance.ingestion..", "com.smarthealthfinance.analytics..",
				"com.smarthealthfinance.shared..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("com.smarthealthfinance.health..")
		.allowEmptyShould(true)
		.because("o Health é derivado; nenhum módulo depende dele sem decisão registrada (ADR-0018)");

	@ArchTest
	static final ArchRule healthOnlyReadsPortsAndDomainModels = noClasses().that()
		.resideInAPackage("com.smarthealthfinance.health..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.smarthealthfinance.analytics.application.usecase..",
				"com.smarthealthfinance.analytics.application.service..",
				"com.smarthealthfinance.analytics.infrastructure..", "com.smarthealthfinance.analytics.presentation..",
				"com.smarthealthfinance.overview.application.usecase..",
				"com.smarthealthfinance.overview.infrastructure..", "com.smarthealthfinance.overview.presentation..",
				"com.smarthealthfinance.accounts.application..", "com.smarthealthfinance.accounts.infrastructure..",
				"com.smarthealthfinance.accounts.presentation..", "com.smarthealthfinance.transactions.application..",
				"com.smarthealthfinance.transactions.infrastructure..",
				"com.smarthealthfinance.transactions.presentation..", "com.smarthealthfinance.identity.application.usecase..",
				"com.smarthealthfinance.identity.infrastructure..", "com.smarthealthfinance.ingestion..")
		.allowEmptyShould(true)
		.because("o Health lê portas e modelos de domínio de Analytics, Overview e Accounts; nunca casos de uso ou "
				+ "adapters de outros módulos, e conhece os eventos só pelo nome (ADR-0018 §1)");

	@ArchTest
	static final ArchRule identityDoesNotDependOnFinancialModules = noClasses().that()
		.resideInAPackage("com.smarthealthfinance.identity..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.smarthealthfinance.accounts..", "com.smarthealthfinance.transactions..",
				"com.smarthealthfinance.overview..")
		.allowEmptyShould(true)
		.because("Identity é a base de autorização (ADR-0003) e não conhece dados financeiros");

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
