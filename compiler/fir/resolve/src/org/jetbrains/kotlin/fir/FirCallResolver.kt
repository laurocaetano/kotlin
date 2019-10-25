/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir

import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirMemberDeclaration
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.impl.FirExpressionStub
import org.jetbrains.kotlin.fir.expressions.impl.FirResolvedQualifierImpl
import org.jetbrains.kotlin.fir.references.FirNamedReference
import org.jetbrains.kotlin.fir.references.FirResolvedCallableReference
import org.jetbrains.kotlin.fir.references.impl.FirBackingFieldReferenceImpl
import org.jetbrains.kotlin.fir.references.impl.FirErrorNamedReferenceImpl
import org.jetbrains.kotlin.fir.references.impl.FirResolvedCallableReferenceImpl
import org.jetbrains.kotlin.fir.references.impl.FirSimpleNamedReference
import org.jetbrains.kotlin.fir.resolve.*
import org.jetbrains.kotlin.fir.resolve.calls.*
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutor
import org.jetbrains.kotlin.fir.resolve.transformers.StoreNameReference
import org.jetbrains.kotlin.fir.resolve.transformers.StoreReceiver
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirExpressionsResolveTransformer
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.resultType
import org.jetbrains.kotlin.fir.resolve.transformers.phasedFir
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.impl.FirLocalScope
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.ConeKotlinErrorType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.impl.FirResolvedTypeRefImpl
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.calls.inference.ConstraintSystemBuilder
import org.jetbrains.kotlin.resolve.calls.results.TypeSpecificityComparator

class FirCallResolver(
    private val transformer: FirExpressionsResolveTransformer,
    topLevelScopes: List<FirScope>,
    localScopes: List<FirLocalScope>,
    override val implicitReceiverStack: ImplicitReceiverStack,
    private val qualifiedResolver: FirQualifiedNameResolver
) : BodyResolveComponents by transformer.components {

    private val towerResolver = FirTowerResolver(
        returnTypeCalculator, this, resolutionStageRunner,
        topLevelScopes = topLevelScopes.asReversed(),
        localScopes = localScopes.asReversed()
    )
    private val conflictResolver = ConeOverloadConflictResolver(TypeSpecificityComparator.NONE, inferenceComponents)

    fun resolveCallAndSelectCandidate(functionCall: FirFunctionCall, expectedTypeRef: FirTypeRef?, file: FirFile): FirFunctionCall {
        qualifiedResolver.reset()
        @Suppress("NAME_SHADOWING")
        val functionCall = functionCall.transformExplicitReceiver(transformer, noExpectedType)
            .transformArguments(transformer, null)

        val name = functionCall.calleeReference.name

        val explicitReceiver = functionCall.explicitReceiver
        val arguments = functionCall.arguments
        val typeArguments = functionCall.typeArguments

        val info = CallInfo(
            CallKind.Function,
            explicitReceiver,
            arguments,
            functionCall.safe,
            typeArguments,
            session,
            file,
            transformer.components.container
        ) { it.resultType }
        towerResolver.reset()

        val consumer = createFunctionConsumer(session, name, info, this, towerResolver.collector, towerResolver)
        val result = towerResolver.runResolver(consumer, implicitReceiverStack.receiversAsReversed())
        val bestCandidates = result.bestCandidates()
        val reducedCandidates = if (result.currentApplicability < CandidateApplicability.SYNTHETIC_RESOLVED) {
            bestCandidates.toSet()
        } else {
            conflictResolver.chooseMaximallySpecificCandidates(bestCandidates, discriminateGenerics = false)
        }


/*
        fun isInvoke()

        val resultExpression =

        when {
            successCandidates.singleOrNull() as? ConeCallableSymbol -> {
                FirFunctionCallImpl(functionCall.session, functionCall.psi, safe = functionCall.safe).apply {
                    calleeReference =
                        functionCall.calleeReference.transformSingle(this@FirBodyResolveTransformer, result.successCandidates())
                    explicitReceiver =
                        FirQualifiedAccessExpressionImpl(
                            functionCall.session,
                            functionCall.calleeReference.psi,
                            functionCall.safe
                        ).apply {
                            calleeReference = createResolvedNamedReference(
                                functionCall.calleeReference,
                                result.variableChecker.successCandidates() as List<ConeCallableSymbol>
                            )
                            explicitReceiver = functionCall.explicitReceiver
                        }
                }
            }
            is ApplicabilityChecker -> {
                functionCall.transformCalleeReference(this, result.successCandidates())
            }
            else -> functionCall
        }
*/
        val nameReference = createResolvedNamedReference(
            functionCall.calleeReference,
            reducedCandidates,
            result.currentApplicability
        )

        val resultExpression = functionCall.transformCalleeReference(StoreNameReference, nameReference) as FirFunctionCall
        val candidate = resultExpression.candidate()

        // We need desugaring
        val resultFunctionCall = if (candidate != null && candidate.callInfo != info) {
            functionCall.copy(
                explicitReceiver = candidate.callInfo.explicitReceiver,
                dispatchReceiver = candidate.dispatchReceiverExpression(),
                extensionReceiver = candidate.extensionReceiverExpression(),
                arguments = candidate.callInfo.arguments,
                safe = candidate.callInfo.isSafeCall
            )
        } else {
            resultExpression
        }
        val typeRef = typeFromCallee(resultFunctionCall)
        if (typeRef.type is ConeKotlinErrorType) {
            resultFunctionCall.resultType = typeRef
        }
        return resultFunctionCall
    }

    fun <T : FirQualifiedAccess> resolveVariableAccessAndSelectCandidate(qualifiedAccess: T, file: FirFile): FirStatement {
        val callee = qualifiedAccess.calleeReference as? FirSimpleNamedReference ?: return qualifiedAccess

        qualifiedResolver.initProcessingQualifiedAccess(qualifiedAccess, callee)

        @Suppress("NAME_SHADOWING")
        val qualifiedAccess = qualifiedAccess.transformExplicitReceiver(transformer, noExpectedType)
        qualifiedResolver.replacedQualifier(qualifiedAccess)?.let { return it }

        val info = CallInfo(
            CallKind.VariableAccess,
            qualifiedAccess.explicitReceiver,
            emptyList(),
            qualifiedAccess.safe,
            emptyList(),
            session,
            file,
            transformer.components.container
        ) { it.resultType }
        towerResolver.reset()

        val consumer = createVariableAndObjectConsumer(
            session,
            callee.name,
            info, this,
            towerResolver.collector
        )
        val result = towerResolver.runResolver(consumer, implicitReceiverStack.receiversAsReversed())

        val bestCandidates = result.bestCandidates()
        val reducedCandidates = if (result.currentApplicability < CandidateApplicability.SYNTHETIC_RESOLVED) {
            bestCandidates.toSet()
        } else {
            conflictResolver.chooseMaximallySpecificCandidates(bestCandidates, discriminateGenerics = false)
        }
        val nameReference = createResolvedNamedReference(
            callee,
            reducedCandidates,
            result.currentApplicability
        )

        if (qualifiedAccess.explicitReceiver == null &&
            (reducedCandidates.size <= 1 && result.currentApplicability < CandidateApplicability.SYNTHETIC_RESOLVED)
        ) {
            qualifiedResolver.tryResolveAsQualifier()?.let { return it }
        }

        val referencedSymbol = when (nameReference) {
            is FirResolvedCallableReference -> nameReference.resolvedSymbol
            is FirNamedReferenceWithCandidate -> nameReference.candidateSymbol
            else -> null
        }
        if (referencedSymbol is FirClassLikeSymbol<*>) {
            val classId = referencedSymbol.classId
            return FirResolvedQualifierImpl(nameReference.psi, classId.packageFqName, classId.relativeClassName).apply {
                resultType = typeForQualifier(this)
            }
        }

        if (qualifiedAccess.explicitReceiver == null) {
            qualifiedResolver.reset()
        }

        @Suppress("UNCHECKED_CAST")
        var resultExpression = qualifiedAccess.transformCalleeReference(StoreNameReference, nameReference) as T
        if (reducedCandidates.size == 1) {
            val candidate = reducedCandidates.single()
            resultExpression = resultExpression.transformDispatchReceiver(StoreReceiver, candidate.dispatchReceiverExpression()) as T
            resultExpression = resultExpression.transformExtensionReceiver(StoreReceiver, candidate.extensionReceiverExpression()) as T
        }
        if (resultExpression is FirExpression) transformer.storeTypeFromCallee(resultExpression)
        return resultExpression
    }

    fun resolveCallableReference(
        constraintSystemBuilder: ConstraintSystemBuilder,
        resolvedCallableReferenceAtom: ResolvedCallableReferenceAtom
    ): Boolean {
        val callableReferenceAccess = resolvedCallableReferenceAtom.atom
        val lhs = resolvedCallableReferenceAtom.lhs
        val coneSubstitutor = constraintSystemBuilder.buildCurrentSubstitutor() as ConeSubstitutor
        val expectedType = resolvedCallableReferenceAtom.expectedType?.let(coneSubstitutor::substituteOrSelf)

        val result = CandidateCollector(this, resolutionStageRunner)
        val consumer =
            createCallableReferencesConsumerForLHS(
                callableReferenceAccess, lhs,
                result, expectedType,
                constraintSystemBuilder
            )

        towerResolver.runResolver(consumer, implicitReceiverStack.receiversAsReversed())
        val bestCandidates = result.bestCandidates()
        val noSuccessfulCandidates = result.currentApplicability < CandidateApplicability.SYNTHETIC_RESOLVED
        val reducedCandidates = if (noSuccessfulCandidates) {
            bestCandidates.toSet()
        } else {
            conflictResolver.chooseMaximallySpecificCandidates(bestCandidates, discriminateGenerics = false)
        }

        when {
            noSuccessfulCandidates -> {
                return false
            }
            reducedCandidates.size > 1 -> {
                if (resolvedCallableReferenceAtom.postponed) return false
                resolvedCallableReferenceAtom.postponed = true
                return true
            }
        }

        val chosenCandidate = reducedCandidates.single()
        constraintSystemBuilder.runTransaction {
            chosenCandidate.outerConstraintBuilderEffect!!(this)

            true
        }

        resolvedCallableReferenceAtom.resultingCandidate = Pair(chosenCandidate, result.currentApplicability)

        return true
    }

    private fun createCallableReferencesConsumerForLHS(
        callableReferenceAccess: FirCallableReferenceAccess,
        lhs: DoubleColonLHS?,
        resultCollector: CandidateCollector,
        expectedType: ConeKotlinType?,
        outerConstraintSystemBuilder: ConstraintSystemBuilder?
    ): TowerDataConsumer {
        val name = callableReferenceAccess.calleeReference.name

        return when (lhs) {
            is DoubleColonLHS.Expression, null -> createCallableReferencesConsumerForReceiver(
                name, resultCollector, callableReferenceAccess.explicitReceiver, expectedType, outerConstraintSystemBuilder,
                lhs
            )
            is DoubleColonLHS.Type -> createCallableReferencesConsumerForReceivers(
                name,
                resultCollector,
                expectedType,
                outerConstraintSystemBuilder,
                lhs,
                FirExpressionStub(callableReferenceAccess.psi).apply { replaceTypeRef(FirResolvedTypeRefImpl(null, lhs.type)) },
                callableReferenceAccess.explicitReceiver
            )
        }
    }

    private fun createCallableReferencesConsumerForReceivers(
        name: Name,
        resultCollector: CandidateCollector,
        expectedType: ConeKotlinType?,
        outerConstraintSystemBuilder: ConstraintSystemBuilder?,
        lhs: DoubleColonLHS?,
        vararg receivers: FirExpression?
    ): TowerDataConsumer {
        if (receivers.size == 1) {
            return createCallableReferencesConsumerForReceiver(
                name, resultCollector, receivers[0], expectedType, outerConstraintSystemBuilder, lhs
            )
        }

        return PrioritizedTowerDataConsumer(
            resultCollector,
            *Array(receivers.size) { index ->
                createCallableReferencesConsumerForReceiver(
                    name, resultCollector, receivers[index], expectedType, outerConstraintSystemBuilder, lhs
                )
            }
        )
    }

    private fun createCallableReferencesConsumerForReceiver(
        name: Name,
        resultCollector: CandidateCollector,
        receiver: FirExpression?,
        expectedType: ConeKotlinType?,
        outerConstraintSystemBuilder: ConstraintSystemBuilder?,
        lhs: DoubleColonLHS?
    ): TowerDataConsumer {
        val info = CallInfo(
            CallKind.CallableReference,
            receiver,
            emptyList(),
            false,
            emptyList(),
            session,
            file,
            transformer.components.container,
            expectedType,
            outerConstraintSystemBuilder,
            lhs
        ) { it.resultType }

        return createCallableReferencesConsumer(session, name, info, this, resultCollector)
    }

    private fun createResolvedNamedReference(
        namedReference: FirNamedReference,
        candidates: Collection<Candidate>,
        applicability: CandidateApplicability
    ): FirNamedReference {
        val name = namedReference.name
        val psi = namedReference.psi
        return when {
            candidates.isEmpty() -> FirErrorNamedReferenceImpl(
                psi, "Unresolved name: $name"
            )
            applicability < CandidateApplicability.SYNTHETIC_RESOLVED -> {
                FirErrorNamedReferenceImpl(
                    psi,
                    "Inapplicable($applicability): ${candidates.map { describeSymbol(it.symbol) }}"
                )
            }
            candidates.size == 1 -> {
                val candidate = candidates.single()
                val coneSymbol = candidate.symbol
                when {
                    coneSymbol is FirBackingFieldSymbol -> FirBackingFieldReferenceImpl(psi, null, coneSymbol)
                    coneSymbol is FirVariableSymbol && (
                            coneSymbol !is FirPropertySymbol ||
                                    (coneSymbol.phasedFir(session) as FirMemberDeclaration).typeParameters.isEmpty()
                            ) ->
                        FirResolvedCallableReferenceImpl(psi, name, coneSymbol)
                    else -> FirNamedReferenceWithCandidate(psi, name, candidate)
                }
            }
            else -> FirErrorNamedReferenceImpl(
                psi, "Ambiguity: $name, ${candidates.map { describeSymbol(it.symbol) }}"
            )
        }
    }


    private fun describeSymbol(symbol: AbstractFirBasedSymbol<*>): String {
        return when (symbol) {
            is FirClassLikeSymbol<*> -> symbol.classId.asString()
            is FirCallableSymbol<*> -> symbol.callableId.toString()
            else -> "$symbol"
        }
    }
}
