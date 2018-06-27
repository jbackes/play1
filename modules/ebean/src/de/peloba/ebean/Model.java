package de.peloba.ebean;

import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.MappedSuperclass;
import javax.persistence.Version;

@MappedSuperclass
public class Model extends EbeanSupport
{

  @Id
  @GeneratedValue
  protected Long id;

	@Version
	protected int modelVersion;
	
  public Long getId()
  {
    return id;
  }

  @Override
  public Object _key()
  {
    return getId();
  }

	@Override
	public int hashCode() {
		if(getId() == null)
			return super.hashCode();

		return getId().intValue();
	}

	@Override
	public boolean equals(Object object) {
		return object != null && object.getClass() == this.getClass() && this.hashCode() == object.hashCode();
	}
}
