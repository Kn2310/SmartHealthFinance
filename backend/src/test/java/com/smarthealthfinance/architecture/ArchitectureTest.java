package com.smarthealthfinance.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.smarthealthfinance.cards.application.port.CardPortfolio;
import com.smarthealthfinance.forecast.application.port.ForecastReader;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
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

	// Fatura de cartão (adendo de 2026-10-23 ao ADR-0009 §20): identity → accounts → transactions → cards → ingestion.

	@ArchTest
	static final ArchRule ingestionUsesOnlyCardsUseCases = noClasses().that()
		.resideInAPackage("com.smarthealthfinance.ingestion..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.smarthealthfinance.cards.infrastructure..", "com.smarthealthfinance.cards.presentation..",
				"com.smarthealthfinance.cards.application.service..", "com.smarthealthfinance.cards.application.port..",
				"com.smarthealthfinance.cards.domain.repository..", "com.smarthealthfinance.cards.domain.model..")
		.allowEmptyShould(true)
		.because("a fatura importada vira compras e parcelas só pelo caso de uso de Cards, na mesma transação do batch; "
				+ "o Ingestion conhece o cartão pelo id (ADR-0009 §20)");

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
		.because("o Health é derivado; só os Insights leem o snapshot dele (ADR-0018 §1 e adendo, ADR-0022 §1)");

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

	// Insights (ADR-0021/0022) é o último derivado: lê portas e modelos de domínio de Analytics e Health.

	@ArchTest
	static final ArchRule nothingDependsOnInsights = noClasses().that()
		.resideOutsideOfPackages("com.smarthealthfinance.insights..", "com.smarthealthfinance.copilot..")
		.and()
		.resideInAPackage("com.smarthealthfinance..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("com.smarthealthfinance.insights..")
		.allowEmptyShould(true)
		.because("os Insights são derivados; nenhum módulo depende deles, salvo as tools de leitura do Copilot "
				+ "(ADR-0022 §1, adendo ADR-0039)");

	@ArchTest
	static final ArchRule insightsOnlyReadPortsAndDomainModels = noClasses().that()
		.resideInAPackage("com.smarthealthfinance.insights..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.smarthealthfinance.analytics.application.usecase..",
				"com.smarthealthfinance.analytics.application.service..",
				"com.smarthealthfinance.analytics.infrastructure..", "com.smarthealthfinance.analytics.presentation..",
				"com.smarthealthfinance.health.application.usecase..",
				"com.smarthealthfinance.health.application.service..",
				"com.smarthealthfinance.health.infrastructure..", "com.smarthealthfinance.health.presentation..",
				"com.smarthealthfinance.overview.application.usecase..",
				"com.smarthealthfinance.overview.infrastructure..", "com.smarthealthfinance.overview.presentation..",
				"com.smarthealthfinance.accounts..",
				"com.smarthealthfinance.transactions.application..",
				"com.smarthealthfinance.transactions.infrastructure..",
				"com.smarthealthfinance.transactions.presentation..", "com.smarthealthfinance.identity.application.usecase..",
				"com.smarthealthfinance.identity.infrastructure..", "com.smarthealthfinance.ingestion..")
		.orShould()
		.dependOnClassesThat()
		.haveFullyQualifiedName("com.smarthealthfinance.health.application.port.HealthSnapshotStore")
		.allowEmptyShould(true)
		.because("os Insights leem só portas de leitura e modelos de domínio de Analytics, Health, Overview e "
				+ "Transactions (nunca a porta de "
				+ "escrita do snapshot), nunca casos de uso ou adapters, e conhecem os eventos só pelo nome (ADR-0022 §1, "
				+ "ADR-0023)");

	// Forecast (ADR-0030/0031 §1) é read model downstream de Analytics, Overview, Cards e Accounts; só os Insights
	// (e, na Sessão 7.2, Goals e Simulation) leem o snapshot, pela porta ForecastReader e pelo domínio.

	@ArchTest
	static final ArchRule nothingExceptInsightsDependsOnForecast = noClasses().that()
		.resideInAPackage("com.smarthealthfinance..")
		.and()
		.resideOutsideOfPackages("com.smarthealthfinance.forecast..", "com.smarthealthfinance.insights..",
				"com.smarthealthfinance.goals..", "com.smarthealthfinance.copilot..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("com.smarthealthfinance.forecast..")
		.allowEmptyShould(true)
		.because("o Forecast é derivado; só os Insights, os Goals e as tools do Copilot o leem (ADR-0031 §1, "
				+ "adendos ADR-0036 e ADR-0039)");

	@ArchTest
	static final ArchRule goalsOnlyReadTheForecastThroughItsReaderAndDomain = noClasses().that()
		.resideInAPackage("com.smarthealthfinance.goals..")
		.should()
		.dependOnClassesThat(JavaClass.Predicates.resideInAPackage("com.smarthealthfinance.forecast..")
			.and(DescribedPredicate.not(JavaClass.Predicates.resideInAPackage("com.smarthealthfinance.forecast.domain.."))
				.and(DescribedPredicate.not(JavaClass.Predicates.belongToAnyOf(ForecastReader.class)))))
		.allowEmptyShould(true)
		.because("os Goals leem o Forecast só pela porta ForecastReader e pelo domínio e aplicam deltas SIMULATED "
				+ "por cima, sem gravar nada nele (ADR-0030 'Para a Sessão 7.2', ADR-0036)");

	@ArchTest
	static final ArchRule insightsOnlyReadTheForecastThroughItsReaderAndDomain = noClasses().that()
		.resideInAPackage("com.smarthealthfinance.insights..")
		.should()
		.dependOnClassesThat(JavaClass.Predicates.resideInAPackage("com.smarthealthfinance.forecast..")
			.and(DescribedPredicate.not(JavaClass.Predicates.resideInAPackage("com.smarthealthfinance.forecast.domain.."))
				.and(DescribedPredicate.not(JavaClass.Predicates.belongToAnyOf(ForecastReader.class)))))
		.allowEmptyShould(true)
		.because("os Insights leem o Forecast só pela porta ForecastReader (sem a porta de escrita do snapshot) e pelo "
				+ "domínio, nunca por casos de uso, serviços ou adapters (ADR-0031 §1)");

	@ArchTest
	static final ArchRule forecastOnlyReadsPortsAndDomainModels = noClasses().that()
		.resideInAPackage("com.smarthealthfinance.forecast..")
		.should()
		.dependOnClassesThat(JavaClass.Predicates
			.resideInAnyPackage("com.smarthealthfinance.analytics..", "com.smarthealthfinance.overview..",
					"com.smarthealthfinance.cards..", "com.smarthealthfinance.accounts..",
					"com.smarthealthfinance.transactions..", "com.smarthealthfinance.identity..",
					"com.smarthealthfinance.ingestion..", "com.smarthealthfinance.health..",
					"com.smarthealthfinance.insights..")
			.and(DescribedPredicate.not(JavaClass.Predicates.resideInAnyPackage(
					"com.smarthealthfinance.analytics.application.port..", "com.smarthealthfinance.analytics.domain..",
					"com.smarthealthfinance.overview.application.port..", "com.smarthealthfinance.overview.domain..",
					"com.smarthealthfinance.cards.domain..", "com.smarthealthfinance.accounts.domain..",
					"com.smarthealthfinance.transactions.domain..", "com.smarthealthfinance.identity.domain..",
					"com.smarthealthfinance.identity.application.service..",
					"com.smarthealthfinance.identity.application.exception.."))
				.and(DescribedPredicate.not(JavaClass.Predicates
					.belongToAnyOf(CardPortfolio.class)))))
		.allowEmptyShould(true)
		.because("o Forecast lê só portas e modelos de domínio de Analytics, Overview, Cards (CardPortfolio), Accounts "
				+ "e Transactions, mais Identity para Workspace e autorização; nunca casos de uso ou adapters de "
				+ "outros módulos, nem Health nem Insights (ADR-0031 §1)");

	// Cards (ADR-0025/0026) fica a jusante do Financial Core: identity → accounts → transactions → cards.

	@ArchTest
	static final ArchRule nothingUpstreamDependsOnCards = noClasses().that()
		.resideInAnyPackage("com.smarthealthfinance.identity..", "com.smarthealthfinance.accounts..",
				"com.smarthealthfinance.transactions..", "com.smarthealthfinance.analytics..",
				"com.smarthealthfinance.shared..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("com.smarthealthfinance.cards..")
		.allowEmptyShould(true)
		.because("Cards conhece o cartão como referência; o núcleo e o Analytics não dependem de Cards (ADR-0025 §1, "
				+ "ADR-0026 §9); a importação de fatura usa Cards por regra própria (ADR-0009 §20)");

	@ArchTest
	static final ArchRule readModelsOnlyReadTheCardPortfolio = noClasses().that()
		.resideInAnyPackage("com.smarthealthfinance.overview..", "com.smarthealthfinance.health..",
				"com.smarthealthfinance.insights..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.smarthealthfinance.cards.application.usecase..",
				"com.smarthealthfinance.cards.application.service..", "com.smarthealthfinance.cards.application.dto..",
				"com.smarthealthfinance.cards.application.exception..", "com.smarthealthfinance.cards.infrastructure..",
				"com.smarthealthfinance.cards.presentation..")
		.allowEmptyShould(true)
		.because("Overview, Health e Insights leem os cartões só pela porta CardPortfolio e pelos modelos de "
				+ "domínio de Cards, nunca por casos de uso, serviços ou adapters (ADR-0006 §18, ADR-0027 §1)");

	// Copilot (ADR-0039 §2, §4) fica na ponta: ninguém depende dele, ele não toca banco fora do próprio ledger e só lê
	// os outros módulos por uma lista fechada (a Parte C acrescenta os casos de uso de leitura das tools).

	/** Pacotes do produto que o Copilot pode usar. Fora do produto (JDK, Spring, SDK), sem restrição aqui. */
	static final String[] COPILOT_ALLOWED = {"com.smarthealthfinance.copilot..", "com.smarthealthfinance.shared..",
			"com.smarthealthfinance.identity.application.exception..",
			"com.smarthealthfinance.identity.domain.valueobject..",
			"com.smarthealthfinance.consents.application.port..", "com.smarthealthfinance.consents.domain.enums..",
			// Parte C: o que os casos de uso das tools devolvem (DTOs e os tipos de domínio dentro deles).
			"com.smarthealthfinance.overview.application.dto..", "com.smarthealthfinance.analytics.application.dto..",
			"com.smarthealthfinance.health.application.dto..", "com.smarthealthfinance.insights.application.dto..",
			"com.smarthealthfinance.cards.application.dto..", "com.smarthealthfinance.forecast.application.dto..",
			"com.smarthealthfinance.goals.application.dto..", "com.smarthealthfinance.transactions.application.dto..",
			"com.smarthealthfinance.(overview|analytics|health|insights|cards|forecast|goals|accounts|transactions)"
					+ ".domain.(enums|model|valueobject).."};

	/** Casos de uso de leitura e simulação das tools (ADR-0039 §4 e adendos da Parte C) e os records de entrada deles. */
	static final Class<?>[] COPILOT_TOOL_USE_CASES = {
			com.smarthealthfinance.overview.application.usecase.GetFinancialOverview.class,
			com.smarthealthfinance.analytics.application.usecase.AnalysisQueries.class,
			com.smarthealthfinance.analytics.application.service.AnalysisContextLoader.Query.class,
			com.smarthealthfinance.health.application.usecase.HealthQueries.class,
			com.smarthealthfinance.health.application.usecase.GetHealthStatus.class,
			com.smarthealthfinance.insights.application.usecase.InsightQueries.class,
			com.smarthealthfinance.cards.application.usecase.GetCardsSummary.class,
			com.smarthealthfinance.cards.application.usecase.GetInvoice.class,
			com.smarthealthfinance.cards.application.usecase.GetFutureInvoices.class,
			com.smarthealthfinance.cards.application.usecase.GetInstallmentPlans.class,
			com.smarthealthfinance.cards.application.usecase.GetCardUtilization.class,
			com.smarthealthfinance.forecast.application.usecase.ForecastQueries.class,
			com.smarthealthfinance.goals.application.usecase.ListGoals.class,
			com.smarthealthfinance.goals.application.usecase.GetGoal.class,
			com.smarthealthfinance.goals.application.usecase.GetGoalProjection.class,
			com.smarthealthfinance.goals.application.usecase.SimulateGoal.class,
			com.smarthealthfinance.goals.application.service.GoalSimulator.Scenario.class,
			com.smarthealthfinance.transactions.application.usecase.ListCategories.class};

	@ArchTest
	static final ArchRule nothingDependsOnCopilot = noClasses().that()
		.resideInAPackage("com.smarthealthfinance..")
		.and()
		.resideOutsideOfPackage("com.smarthealthfinance.copilot..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("com.smarthealthfinance.copilot..")
		.allowEmptyShould(true)
		.because("o Copilot só consome; falha de IA nunca pode alcançar o núcleo financeiro (ADR-0039 §2)");

	@ArchTest
	static final ArchRule copilotOnlyUsesTheClosedList = classes().that()
		.resideInAPackage("com.smarthealthfinance.copilot..")
		.should()
		.onlyDependOnClassesThat(JavaClass.Predicates.resideOutsideOfPackage("com.smarthealthfinance..")
			.or(JavaClass.Predicates.resideInAnyPackage(COPILOT_ALLOWED))
			// Do Identity, só a autorização: usuário corrente e guard de membership.
			.or(JavaClass.Predicates.equivalentTo(
					com.smarthealthfinance.identity.application.service.CurrentUserService.class))
			.or(JavaClass.Predicates.equivalentTo(
					com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard.class))
			.or(copilotToolUseCase()))
		.allowEmptyShould(true)
		.because("o Copilot lê os outros módulos só por uma lista fechada: autorização, a porta de consentimento e, na "
				+ "Parte C, os casos de uso de leitura das tools; nunca repositórios, adapters ou escrita (ADR-0039 §4)");

	private static com.tngtech.archunit.base.DescribedPredicate<JavaClass> copilotToolUseCase() {
		java.util.Set<String> names = java.util.Arrays.stream(COPILOT_TOOL_USE_CASES).map(Class::getName)
			.collect(java.util.stream.Collectors.toSet());
		return com.tngtech.archunit.base.DescribedPredicate.describe("a closed-list tool use case or its nested record",
				javaClass -> names.contains(javaClass.getName()) || names.contains(topLevel(javaClass.getName())));
	}

	/** Records aninhados de um caso de uso da lista (Query, Projection) valem como o próprio caso de uso. */
	private static String topLevel(String className) {
		int nested = className.indexOf('$');
		return nested < 0 ? className : className.substring(0, nested);
	}

	@ArchTest
	static final ArchRule copilotTouchesTheDatabaseOnlyInItsLedgerAdapter = noClasses().that()
		.resideInAPackage("com.smarthealthfinance.copilot..")
		.and()
		.resideOutsideOfPackage("com.smarthealthfinance.copilot.infrastructure.persistence..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("org.springframework.jdbc..", "jakarta.persistence..", "org.springframework.data.jpa..",
				"org.springframework.data.repository..", "java.sql..", "javax.sql..")
		.allowEmptyShould(true)
		.because("a IA nunca acessa o banco: só o adapter do usage ledger grava na própria tabela (ADR-0039 §2, §10)");

	// Consents (ADR-0038 §1, §6) só conhece a autorização do Identity e nenhum dado financeiro. Outro módulo só usa a
	// porta ConsentReader e o enum das finalidades.

	@ArchTest
	static final ArchRule consentsDoesNotKnowFinancialModules = noClasses().that()
		.resideInAPackage("com.smarthealthfinance.consents..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.smarthealthfinance.accounts..", "com.smarthealthfinance.transactions..",
				"com.smarthealthfinance.ingestion..", "com.smarthealthfinance.overview..",
				"com.smarthealthfinance.analytics..", "com.smarthealthfinance.health..",
				"com.smarthealthfinance.insights..", "com.smarthealthfinance.forecast..",
				"com.smarthealthfinance.goals..", "com.smarthealthfinance.cards..",
				"com.smarthealthfinance.identity.application.usecase..",
				"com.smarthealthfinance.identity.infrastructure..", "com.smarthealthfinance.identity.presentation..")
		.allowEmptyShould(true)
		.because("os consentimentos só dependem da autorização do Identity e não leem dado financeiro (ADR-0038 §1)");

	@ArchTest
	static final ArchRule otherModulesUseOnlyTheConsentReader = noClasses().that()
		.resideInAPackage("com.smarthealthfinance..")
		.and()
		.resideOutsideOfPackage("com.smarthealthfinance.consents..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.smarthealthfinance.consents.application.usecase..",
				"com.smarthealthfinance.consents.application.service..",
				"com.smarthealthfinance.consents.application.dto..",
				"com.smarthealthfinance.consents.application.exception..",
				"com.smarthealthfinance.consents.domain.model..",
				"com.smarthealthfinance.consents.domain.repository..",
				"com.smarthealthfinance.consents.domain.valueobject..",
				"com.smarthealthfinance.consents.domain.exception..",
				"com.smarthealthfinance.consents.infrastructure..", "com.smarthealthfinance.consents.presentation..")
		.allowEmptyShould(true)
		.because("outro módulo verifica consentimento só pela porta ConsentReader e pelo enum ConsentPurpose "
				+ "(ADR-0038 §6)");

	// Goals (ADR-0034 §8, ADR-0035) fica a jusante: lê saldos e movimentos pelas portas do Overview e pelo domínio de
	// Accounts e Transactions, e lança o aporte só pelo caso de uso CreateTransaction. Ninguém depende de Goals.

	@ArchTest
	static final ArchRule nothingDependsOnGoals = noClasses().that()
		.resideInAPackage("com.smarthealthfinance..")
		.and()
		.resideOutsideOfPackages("com.smarthealthfinance.goals..", "com.smarthealthfinance.copilot..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("com.smarthealthfinance.goals..")
		.allowEmptyShould(true)
		.because("os objetivos são lidos só pela própria API e pelas tools do Copilot; Health e Insights se "
				+ "ligam a eles pelo frontend (ADR-0034 §7, adendo ADR-0039)");

	@ArchTest
	static final ArchRule goalsOnlyUseCorePortsDomainAndTheTransferUseCase = noClasses().that()
		.resideInAPackage("com.smarthealthfinance.goals..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.smarthealthfinance.accounts.application.usecase..",
				"com.smarthealthfinance.accounts.application.service..",
				"com.smarthealthfinance.accounts.infrastructure..", "com.smarthealthfinance.accounts.presentation..",
				"com.smarthealthfinance.transactions.infrastructure..",
				"com.smarthealthfinance.transactions.application.service..",
				"com.smarthealthfinance.transactions.application.port..",
				"com.smarthealthfinance.transactions.presentation.controller..",
				"com.smarthealthfinance.transactions.presentation.handler..",
				"com.smarthealthfinance.transactions.presentation.dto.request..",
				"com.smarthealthfinance.overview.application.usecase..",
				"com.smarthealthfinance.overview.application.service..",
				"com.smarthealthfinance.overview.infrastructure..", "com.smarthealthfinance.overview.presentation..",
				"com.smarthealthfinance.ingestion..", "com.smarthealthfinance.analytics.application.usecase..",
				"com.smarthealthfinance.analytics.application.service..",
				"com.smarthealthfinance.analytics.application.dto..",
				"com.smarthealthfinance.analytics.infrastructure..", "com.smarthealthfinance.analytics.presentation..",
				"com.smarthealthfinance.cards..", "com.smarthealthfinance.health..",
				"com.smarthealthfinance.insights..", "com.smarthealthfinance.identity.application.usecase..",
				"com.smarthealthfinance.identity.infrastructure..")
		.allowEmptyShould(true)
		.because("Goals lê contas e transações pelo domínio, saldos pela porta do Overview, médias pela porta e pelo "
				+ "domínio do Analytics e lança o aporte só pelo caso de uso CreateTransaction; nunca lê o Health "
				+ "(ADR-0035, ADR-0036)");

	@ArchTest
	static final ArchRule cardsOnlyUsesCorePortsAndDomain = noClasses().that()
		.resideInAPackage("com.smarthealthfinance.cards..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.smarthealthfinance.accounts.application.usecase..",
				"com.smarthealthfinance.accounts.infrastructure..", "com.smarthealthfinance.accounts.presentation..",
				"com.smarthealthfinance.transactions.infrastructure..",
				"com.smarthealthfinance.transactions.presentation.controller..",
				"com.smarthealthfinance.transactions.presentation.handler..",
				"com.smarthealthfinance.transactions.presentation.dto.request..", "com.smarthealthfinance.ingestion..",
				"com.smarthealthfinance.overview..", "com.smarthealthfinance.analytics..",
				"com.smarthealthfinance.health..", "com.smarthealthfinance.insights..",
				"com.smarthealthfinance.identity.application.usecase..",
				"com.smarthealthfinance.identity.infrastructure..")
		.allowEmptyShould(true)
		.because("Cards lê contas pela porta de domínio e lança transações só pelos casos de uso de Transactions, "
				+ "nunca pelos adapters; a resposta reusa o TransactionResponse para o contrato ser um só "
				+ "(ADR-0025 §1, ADR-0026 §10)");

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
