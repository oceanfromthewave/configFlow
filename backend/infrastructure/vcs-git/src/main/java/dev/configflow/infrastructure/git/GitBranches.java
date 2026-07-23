package dev.configflow.infrastructure.git;

import dev.configflow.domain.vcs.exception.MergeConflictException;
import dev.configflow.domain.vcs.exception.VcsException;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.MergeRequest;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.CannotDeleteCurrentBranchException;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.NotMergedException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;

/**
 * Branch mutations for Git: checkout, create, delete, merge.
 *
 * <p>Held by {@link GitVcsProvider}, which exposes these through the
 * {@code BranchOperations} port. Every method here is synchronous; running them off the
 * request thread is the application's job.</p>
 */
final class GitBranches {

    private final GitRepositoryAccess access;

    GitBranches(GitRepositoryAccess access) {
        this.access = access;
    }

    void checkout(RepositoryHandle repo, String ref) {
        try (Git git = access.open(repo)) {
            git.checkout().setName(ref).call();
        } catch (RefNotFoundException e) {
            throw new NoSuchElementException("Ref not found: " + ref);
        } catch (CheckoutConflictException e) {
            // Local edits would be overwritten. Not our bug and not a lost cause: the
            // user can stash or commit and retry, so say which files are in the way.
            throw new VcsPreconditionException(
                    "Local changes would be overwritten by checkout: "
                            + String.join(", ", e.getConflictingPaths()), e);
        } catch (InvalidRefNameException | RevisionSyntaxException e) {
            throw new IllegalArgumentException("Not a valid ref: " + ref, e);
        } catch (GitAPIException e) {
            throw new VcsException("Failed to check out " + ref + " in " + repo.localPath(), e);
        }
    }

    void createBranch(RepositoryHandle repo, String name, String startPoint, boolean checkout) {
        try (Git git = access.open(repo)) {
            CreateBranchCommand create = git.branchCreate().setName(name);
            if (startPoint != null && !startPoint.isBlank()) {
                create.setStartPoint(startPoint);
            }
            create.call();
            if (checkout) {
                git.checkout().setName(name).call();
            }
        } catch (RefAlreadyExistsException e) {
            throw new VcsPreconditionException("Branch already exists: " + name, e);
        } catch (RefNotFoundException e) {
            throw new NoSuchElementException("Start point not found: " + startPoint);
        } catch (InvalidRefNameException | RevisionSyntaxException e) {
            throw new IllegalArgumentException("Not a valid branch name: " + name, e);
        } catch (GitAPIException e) {
            throw new VcsException("Failed to create branch " + name, e);
        }
    }

    void deleteBranch(RepositoryHandle repo, String name, boolean remote, boolean force) {
        try (Git git = access.open(repo)) {
            // JGit deletes by full ref name for remotes; locals accept the short form.
            String target = remote ? Constants.R_REMOTES + name : name;
            List<String> deleted =
                    git.branchDelete().setBranchNames(target).setForce(force).call();
            if (deleted.isEmpty()) {
                // JGit reports "deleted nothing" by returning an empty list rather than
                // failing, so without this a typo would be reported as a success.
                throw new NoSuchElementException("Branch not found: " + name);
            }
        } catch (NotMergedException e) {
            // Refusing here is the point: without force this would discard commits.
            throw new VcsPreconditionException(
                    "Branch " + name + " is not fully merged; delete with force to discard it", e);
        } catch (CannotDeleteCurrentBranchException e) {
            throw new VcsPreconditionException(
                    "Cannot delete " + name + " while it is checked out", e);
        } catch (GitAPIException e) {
            throw new VcsException("Failed to delete branch " + name, e);
        }
    }

    void merge(RepositoryHandle repo, MergeRequest request) {
        try (Git git = access.open(repo)) {
            Repository repository = git.getRepository();
            Ref source = resolveMergeSource(repository, request.source());

            MergeCommand merge = git.merge().include(source);
            if (request.fastForwardOnly()) {
                merge.setFastForward(MergeCommand.FastForwardMode.FF_ONLY);
            }
            if (request.squash()) {
                merge.setSquash(true);
            }
            MergeResult result = merge.call();

            MergeResult.MergeStatus status = result.getMergeStatus();
            if (status == MergeResult.MergeStatus.CONFLICTING) {
                // The working tree is left conflicted on purpose: the user resolves it.
                throw new MergeConflictException(conflictedPaths(result));
            }
            if (status == MergeResult.MergeStatus.ABORTED
                    || status == MergeResult.MergeStatus.FAILED) {
                // Nothing went wrong on our side — the history simply cannot be merged
                // the way it was asked for, typically fast-forward-only against a branch
                // that has diverged. The user picks a different mode or rebases first.
                throw new VcsPreconditionException(
                        "Cannot merge " + request.source() + ": " + status);
            }
            if (!status.isSuccessful()) {
                throw new VcsException(
                        "Merge of " + request.source() + " failed: " + status);
            }
        } catch (RevisionSyntaxException | AmbiguousObjectException e) {
            throw new IllegalArgumentException("Not a valid ref: " + request.source(), e);
        } catch (IOException e) {
            throw new VcsException("Failed to merge " + request.source(), e);
        } catch (GitAPIException e) {
            throw new VcsException("Failed to merge " + request.source(), e);
        }
    }

    /**
     * JGit's merge takes a {@link Ref}, so a plain revision has to be wrapped in one.
     */
    private static Ref resolveMergeSource(Repository repository, String source)
            throws IOException {
        Ref ref = repository.findRef(source);
        if (ref != null) {
            return ref;
        }
        ObjectId id = repository.resolve(source);
        if (id == null) {
            throw new NoSuchElementException("Ref not found: " + source);
        }
        return new org.eclipse.jgit.lib.ObjectIdRef.Unpeeled(
                Ref.Storage.LOOSE, source, id);
    }

    private static List<Path> conflictedPaths(MergeResult result) {
        List<Path> paths = new ArrayList<>();
        if (result.getConflicts() != null) {
            for (String path : result.getConflicts().keySet()) {
                paths.add(Path.of(path));
            }
        }
        return paths;
    }
}
