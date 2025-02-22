/* SPDX-License-Identifier: Apache-2.0 */

package marquez.service;

import marquez.db.BaseDao;

public class DatasetVersionService extends DelegatingDaos.DelegatingDatasetVersionDao {
  public DatasetVersionService(BaseDao baseDao) {
    super(baseDao.createDatasetVersionDao());
  }
}
