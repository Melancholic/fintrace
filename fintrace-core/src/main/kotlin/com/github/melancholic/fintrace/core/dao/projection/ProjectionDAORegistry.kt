package com.github.melancholic.fintrace.core.dao.projection

import com.github.melancholic.fintrace.core.domain.projection.Projection
import com.github.melancholic.fintrace.core.service.projection.ProjectionTarget
import org.springframework.stereotype.Component


interface ProjectionDAORegistry {
    fun <P : Projection> resolve(candidateClass: Class<P>): ProjectionDAO<P>
    fun resolve(target: ProjectionTarget): ProjectionDAO<out Projection>
    operator fun <D : ProjectionDAO<out Projection>> get(candidateClass: Class<D>): D
    fun asList(): List<ProjectionDAO<out Projection>>
}

@Component
class ProjectionDAORegistryImpl(
    private val projectionDAOList: List<ProjectionDAO<*>>
) : ProjectionDAORegistry {
    private val daoByProjectionClassMap: Map<Class<out Projection>, ProjectionDAO<out Projection>> =
        projectionDAOList.associateBy { it.supportedClass() }
    private val daoByProjectionTargetMap: Map<ProjectionTarget, ProjectionDAO<*>> =
        projectionDAOList.associateBy { it.projectionTarget() }

    @Suppress("UNCHECKED_CAST")
    override fun <P : Projection> resolve(candidateClass: Class<P>): ProjectionDAO<P> {
        return daoByProjectionClassMap[candidateClass] as? ProjectionDAO<P>
            ?: throw IllegalStateException("Projection DAO for ProjectionClass=${candidateClass.javaClass.name} was not found")

    }

    override fun resolve(target: ProjectionTarget): ProjectionDAO<out Projection> {
        return daoByProjectionTargetMap[target]
            ?: throw IllegalStateException("Projection DAO for ProjectionTarget=${target} was not found")
    }

    override operator fun <D : ProjectionDAO<out Projection>> get(candidateClass: Class<D>): D {
        val matches = projectionDAOList.filter { candidateClass.isInstance(it) }

        return when (matches.size) {
            1 -> candidateClass.cast(matches.single())
            0 -> throw IllegalStateException("No projection DAO of type ${candidateClass.name}")
            else -> throw IllegalStateException(
                "${matches.size} projection DAOs match ${candidateClass.name}: " +
                        matches.joinToString { it::class.java.name }
            )
        }
    }

    override fun asList(): List<ProjectionDAO<out Projection>> = projectionDAOList
}