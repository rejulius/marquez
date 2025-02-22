/* SPDX-License-Identifier: Apache-2.0 */

package marquez.db;

import static marquez.db.DbTestUtils.createJobWithSymlinkTarget;
import static marquez.db.DbTestUtils.createJobWithoutSymlinkTarget;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import marquez.db.models.DbModelGenerator;
import marquez.db.models.JobRow;
import marquez.db.models.NamespaceRow;
import marquez.jdbi.MarquezJdbiExternalPostgresExtension;
import marquez.service.models.Job;
import org.assertj.core.api.AbstractObjectAssert;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MarquezJdbiExternalPostgresExtension.class)
public class JobDaoTest {

  private static JobDao jobDao;
  private static NamespaceDao namespaceDao;
  private static NamespaceRow namespace;
  private static Jdbi jdbi;

  @BeforeAll
  public static void setUpOnce(Jdbi jdbi) {
    JobDaoTest.jdbi = jdbi;
    jobDao = jdbi.onDemand(JobDao.class);
    namespaceDao = jdbi.onDemand(NamespaceDao.class);
    namespace =
        namespaceDao.upsertNamespaceRow(
            UUID.randomUUID(),
            Instant.now(),
            JobDaoTest.class.getSimpleName(),
            JobDaoTest.class.getName());
  }

  @AfterEach
  public void cleanUp(Jdbi jdbi) {
    jdbi.inTransaction(h -> h.execute("DELETE FROM jobs"));
  }

  @Test
  public void emptyUrl() {
    assertNull(jobDao.toUrlString(null));
  }

  @Test
  public void testFindSymlinkedJobByName() {
    JobRow targetJob =
        createJobWithoutSymlinkTarget(jdbi, namespace, "targetJob", "the target of the symlink");
    JobRow symlinkJob =
        createJobWithSymlinkTarget(
            jdbi, namespace, "symlinkJob", targetJob.getUuid(), "the symlink job");

    Optional<Job> jobByName =
        jobDao.findJobByName(symlinkJob.getNamespaceName(), symlinkJob.getName());

    assertJobEquals(jobByName, targetJob.getNamespaceName(), targetJob.getName());
  }

  @Test
  public void testFindSymlinkedJobRowByName() {
    JobRow targetJob =
        createJobWithoutSymlinkTarget(jdbi, namespace, "targetJob", "the target of the symlink");
    JobRow symlinkJob =
        createJobWithSymlinkTarget(
            jdbi, namespace, "symlinkJob", targetJob.getUuid(), "the symlink job");

    Optional<JobRow> jobByName =
        jobDao.findJobByNameAsRow(symlinkJob.getNamespaceName(), symlinkJob.getName());
    assertThat(jobByName)
        .isPresent()
        .get()
        .hasFieldOrPropertyWithValue("name", targetJob.getName())
        .hasFieldOrPropertyWithValue("namespaceName", targetJob.getNamespaceName());
  }

  @Test
  public void testFindAll() {
    JobRow targetJob =
        createJobWithoutSymlinkTarget(jdbi, namespace, "targetJob", "the target of the symlink");
    JobRow symlinkJob =
        createJobWithSymlinkTarget(
            jdbi, namespace, "symlinkJob", targetJob.getUuid(), "the symlink job");
    JobRow anotherJobSameNamespace =
        createJobWithoutSymlinkTarget(jdbi, namespace, "anotherJob", "a random other job");

    List<Job> jobs = jobDao.findAll(namespace.getName(), 10, 0);

    // the symlinked job isn't present in the response - only the symlink target and the job with
    // no symlink
    assertThat(jobs)
        .hasSize(2)
        .map(Job::getId)
        .containsExactlyInAnyOrder(
            DbModelGenerator.jobIdFor(namespace.getName(), targetJob.getName()),
            DbModelGenerator.jobIdFor(namespace.getName(), anotherJobSameNamespace.getName()));
  }

  @Test
  public void testCountFor() {
    JobRow targetJob =
        createJobWithoutSymlinkTarget(jdbi, namespace, "targetJob", "the target of the symlink");
    createJobWithSymlinkTarget(
        jdbi, namespace, "symlinkJob", targetJob.getUuid(), "the symlink job");
    createJobWithoutSymlinkTarget(jdbi, namespace, "anotherJob", "a random other job");
    createJobWithoutSymlinkTarget(jdbi, namespace, "aThirdJob", "a random third job");

    NamespaceRow anotherNamespace =
        namespaceDao.upsertNamespaceRow(
            UUID.randomUUID(), Instant.now(), "anotherNamespace", getClass().getName());
    createJobWithSymlinkTarget(
        jdbi, anotherNamespace, "othernamespacejob", null, "job in another namespace");

    assertThat(jobDao.count()).isEqualTo(4);

    assertThat(jobDao.countFor(namespace.getName())).isEqualTo(3);
  }

  @Test
  public void testUpsertJobWithNewSymlink() {
    JobRow targetJob =
        createJobWithoutSymlinkTarget(jdbi, namespace, "targetJob", "the target of the symlink");

    String symlinkJobName = "symlinkJob";
    JobRow symlinkJob =
        createJobWithoutSymlinkTarget(jdbi, namespace, symlinkJobName, "the symlink job");

    // the job queried is returned, since there is no symlink
    Optional<Job> jobByName =
        jobDao.findJobByName(symlinkJob.getNamespaceName(), symlinkJob.getName());
    assertJobEquals(jobByName, symlinkJob.getNamespaceName(), symlinkJob.getName());

    createJobWithSymlinkTarget(
        jdbi, namespace, symlinkJobName, targetJob.getUuid(), "the symlink job");

    // now the symlink target should be returned
    assertJobEquals(
        jobDao.findJobByName(symlinkJob.getNamespaceName(), symlinkJob.getName()),
        targetJob.getNamespaceName(),
        targetJob.getName());

    // upsert without the symlink target - the previous value should be respected
    createJobWithoutSymlinkTarget(jdbi, namespace, symlinkJobName, "the symlink job");

    // the symlink target should still be returned
    assertJobEquals(
        jobDao.findJobByName(symlinkJob.getNamespaceName(), symlinkJob.getName()),
        targetJob.getNamespaceName(),
        targetJob.getName());
  }

  private AbstractObjectAssert<?, Job> assertJobEquals(
      Optional<Job> jobByName, String namespaceName, String jobName) {
    return assertThat(jobByName)
        .isPresent()
        .get()
        .hasFieldOrPropertyWithValue("id", DbModelGenerator.jobIdFor(namespaceName, jobName));
  }

  @Test
  public void pgObjectException() throws JsonProcessingException {
    ObjectMapper objectMapper = mock(ObjectMapper.class);
    when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException());
    assertNull(jobDao.toJson(null, objectMapper));
  }
}
