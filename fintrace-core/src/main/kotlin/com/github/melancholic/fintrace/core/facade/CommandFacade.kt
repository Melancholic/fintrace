package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.domain.command.Command
import com.github.melancholic.fintrace.core.domain.entity.WorkspaceStatus
import com.github.melancholic.fintrace.core.security.IdentityProvider
import com.github.melancholic.fintrace.core.service.WorkspaceService
import com.github.melancholic.fintrace.core.service.command.CommandDispatcher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

interface CommandFacade {
    fun <R> processCommand(command: Command<R>): R
}

@Service
class CommandFacadeImpl(
    val commandDispatcher: CommandDispatcher,
    val identityProvider: IdentityProvider,
    val workspaceService: WorkspaceService
) : CommandFacade {

    @Transactional
    override fun <R> processCommand(command: Command<R>): R {
        val userId = identityProvider.currentUserId()
        val workspace = workspaceService.requireWritable(userId, command.workspaceId)
        val result = commandDispatcher.dispatch(command)
        if (workspace.status == WorkspaceStatus.NEW) workspaceService.activateWorkspace(userId, command.workspaceId)
        return result    }

}